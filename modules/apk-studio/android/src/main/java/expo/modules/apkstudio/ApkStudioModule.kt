package expo.modules.apkstudio

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.*
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.*
import java.util.zip.*
import android.content.pm.PackageManager

class ApkStudioModule : Module() {
  private val alias = "apkstudio-rsa-2048"

  override fun definition() = ModuleDefinition {
    Name("ApkStudio")

    AsyncFunction("inspect") { uriString: String ->
      val uri = Uri.parse(uriString)
      val apk = copyUriToCache(uri, "inspect.apk")
      val pm = appContext.reactContext!!.packageManager
      val info = pm.getPackageArchiveInfo(apk.absolutePath,
        PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
          PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS or
          PackageManager.GET_PERMISSIONS)
        ?: throw Exception("تعذر قراءة AndroidManifest.xml لهذا APK")
      val ai = info.applicationInfo
      ai.sourceDir = apk.absolutePath
      ai.publicSourceDir = apk.absolutePath
      val label = pm.getApplicationLabel(ai).toString()
      Bundle().apply {
        putString("label", label)
        putString("packageName", info.packageName)
        putString("versionName", info.versionName ?: "")
        putLong("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
        putInt("targetSdk", ai.targetSdkVersion)
        putString("size", humanSize(apk.length()))
      }
    }

    AsyncFunction("list") { uriString: String ->
      val apk = copyUriToCache(Uri.parse(uriString), "list.apk")
      ZipFile(apk).use { z ->
        z.entries().asSequence().map { it.name }.sorted().toList()
      }
    }

    AsyncFunction("extract") { uriString: String ->
      val apk = copyUriToCache(Uri.parse(uriString), "input.apk")
      val root = File(appContext.reactContext!!.getExternalFilesDir(null), "APKStudio/projects")
      root.mkdirs()
      val project = File(root, "project_" + System.currentTimeMillis())
      project.mkdirs()
      ZipFile(apk).use { z ->
        z.entries().asSequence().forEach { entry ->
          val target = safeChild(project, entry.name)
          if (entry.isDirectory) {
            target.mkdirs()
          } else {
            target.parentFile?.mkdirs()
            z.getInputStream(entry).use { input ->
              FileOutputStream(target).use { output -> input.copyTo(output) }
            }
          }
        }
      }
      project.absolutePath
    }

    AsyncFunction("rebuildAndSign") { projectPath: String ->
      val project = File(projectPath)
      if (!project.isDirectory) throw Exception("مجلد المشروع غير موجود")
      val unsigned = File(project.parentFile, project.name + "-unsigned.apk")
      val signed = File(project.parentFile, project.name + "-signed.apk")
      if (unsigned.exists()) unsigned.delete()
      if (signed.exists()) signed.delete()
      zipProject(project, unsigned)
      signApk(unsigned, signed)
      if (!signed.isFile() || signed.length() == 0L) throw Exception("فشل إنشاء APK موقع")
      signed.absolutePath
    }

    AsyncFunction("install") { path: String ->
      val file = File(path)
      if (!file.isFile()) throw Exception("ملف APK غير موجود")
      val context = appContext.reactContext!!
      val uri = FileProvider.getUriForFile(context, "com.apkstudio.mobile.apkstudio.files", file)
      val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    }
  }

  private fun copyUriToCache(uri: Uri, name: String): File {
    val context = appContext.reactContext!!
    val out = File(context.cacheDir, name)
    context.contentResolver.openInputStream(uri)?.use { input ->
      FileOutputStream(out).use { output -> input.copyTo(output, 1024 * 1024) }
    } ?: throw Exception("تعذر فتح ملف APK")
    return out
  }

  private fun safeChild(root: File, name: String): File {
    val target = File(root, name)
    val rootPath = root.canonicalPath + File.separator
    if (!target.canonicalPath.startsWith(rootPath)) throw Exception("مسار غير آمن داخل APK")
    return target
  }

  private fun zipProject(project: File, out: File) {
    val counting = CountingOutputStream(BufferedOutputStream(FileOutputStream(out)))
    ZipOutputStream(counting).use { zip ->
      val files = project.walkTopDown().filter { it.isFile }.toList().sortedBy { it.relativeTo(project).path }
      val buffer = ByteArray(1024 * 1024)
      for (file in files) {
        val rel = file.relativeTo(project).path.replace(File.separatorChar, '/')
        if (rel.startsWith("META-INF/")) continue
        val data = if (rel == "resources.arsc") file.readBytes() else null
        val entry = ZipEntry(rel)
        if (data != null) {
          entry.method = ZipEntry.STORED
          entry.size = data.size.toLong()
          entry.crc = CRC32().apply { update(data) }.value
          val extraLength = alignmentExtraLength(counting.count, rel.toByteArray(Charsets.UTF_8).size)
          if (extraLength > 0) entry.extra = makePaddingExtra(extraLength)
        }
        zip.putNextEntry(entry)
        if (data != null) {
          zip.write(data)
        } else {
          FileInputStream(file).use { input -> input.copyTo(zip, 1024 * 1024) }
        }
        zip.closeEntry()
      }
    }
  }

  private fun alignmentExtraLength(offset: Long, nameLength: Int): Int {
    val base = (offset + 30L + nameLength).toInt()
    val pad = (4 - (base and 3)) and 3
    return if (pad == 0) 0 else pad + 4
  }

  private fun makePaddingExtra(total: Int): ByteArray {
    val out = ByteArray(total)
    out[0] = 0xFE.toByte()
    out[1] = 0xCA.toByte()
    val len = total - 4
    out[2] = (len and 0xFF).toByte()
    out[3] = ((len shr 8) and 0xFF).toByte()
    return out
  }

  private fun signApk(input: File, output: File) {
    val (key, cert) = getSigningIdentity()
    val config = ApkSigner.SignerConfig.Builder("apkstudio", key, listOf(cert)).build()
    ApkSigner.Builder(listOf(config))
      .setInputApk(input)
      .setOutputApk(output)
      .setV1SigningEnabled(true)
      .setV2SigningEnabled(true)
      .setOtherSignersSignaturesPreserved(false)
      .build()
      .sign()
  }

  private fun getSigningIdentity(): Pair<PrivateKey, X509Certificate> {
    val ks = KeyStore.getInstance("AndroidKeyStore")
    ks.load(null)
    if (!ks.containsAlias(alias)) {
      val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
      val now = Date()
      val end = Calendar.getInstance().apply { time = now; add(Calendar.YEAR, 20) }.time
      val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
        .setKeySize(2048)
        .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=APK Studio"))
        .setCertificateSerialNumber(BigInteger.ONE)
        .setCertificateNotBefore(now)
        .setCertificateNotAfter(end)
        .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
        .setUserAuthenticationRequired(false)
        .build()
      generator.initialize(spec)
      generator.generateKeyPair()
      ks.load(null)
    }
    val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
      ?: throw Exception("تعذر الوصول إلى مفتاح التوقيع")
    return Pair(entry.privateKey, entry.certificate as X509Certificate)
  }

  private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
  }

  private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count = 0L
      private set
    override fun write(b: Int) { out.write(b); count++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
  }
}
