package com.timmonet.core

import android.util.Log
import com.timmonet.MainModule
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Tencent Ark 应用包（xarchive 1.0）的在线重打包器。
 *
 * 只处理“合并消息转发”（com.tencent.multimsg）这一种包：它内部 baseView.js
 * 把卡片背景硬编码成 0xFFFFFFFF，并且只在 themeId ∈ {1102,2920,1103} 时走深色
 * 分支。莫奈主题（themeId=2971）永远落到浅色分支，导致“自己转发的聊天记录”
 * 卡片在深色莫奈下依然是纯白。
 *
 * 这里在 TIM 加载包之前（ArkAppCGIMgr.verifyAppPackage 钩子里）就地重打包：
 * 给 baseView.js 注入一段读取 config.token 的逻辑，卡片（无论自己还是别人发的）
 * 统一用对方气泡配色：背景 = bubble_guest（实底，当前莫奈为 #003045），
 * 标题 = bubble_guest_text_primary，正文/底部“聊天记录”小字 =
 * bubble_guest_text_secondary。透明底会让卡片透出页面背景（自己发的卡片原生
 * 没有气泡垫底），所以必须用实底。同时按住卡片时不改变颜色（跳过 hover 换色）。
 * 重打包后校验签名必然失败，因此 verifyAppPackage 钩子对 multisg 直接放行。
 *
 * 格式（已完整逆向 libark-5c4e27.so）：
 *   magic "xarchive 1.0"(16B)
 *   u32 blockSize(=25) | u32 fileCount | u32 indexSize | u8 mode(=1) | keyCheck(16B)
 *   index（indexSize 字节，与 KEY 循环异或）：
 *     每条记录：u32 recLen(nameLen+11) | name | 0x00 | u32 dataOffset | u32 dataSize
 *               | u8 compressed | u8 encrypted
 *   data：按记录顺序存放；encrypted=1 则与 KEY 循环异或，compressed=1 则 zlib。
 * 密钥为固定常量 "20180730104551tm"（全量 .ark 共用），keyCheck 为其 MD5。
 */
object ArkPackagePatcher {

    private const val TAG = MainModule.TAG
    private const val MAGIC_PREFIX = "xarchive 1.0"
    private const val MARKER = "TimMonetPatchV3"
    private const val TARGET_ENTRY = "baseView.js"
    private const val MANNOUNCE_MARKER = "TimMonetMannouncePatch"
    private const val MANNOUNCE_TARGET_ENTRY = "mannounce.lua"

    private val KEY = "20180730104551tm".toByteArray(Charsets.US_ASCII)

    private val PATCH_BLOCK_REGEX =
        Regex("(?s)// ==== TimMonetPatch.*?// ==== end TimMonetPatch ====\\s*")

    private val MANNOUNCE_BLOCK_REGEX = Regex(
        "(?s)-- ==== TimMonetMannouncePatch.*?-- ==== end TimMonetMannouncePatch ====\\s*"
    )


    private enum class Kind { MULTIMSG, MANNOUNCE }

    @Volatile
    private var patchedPath: String? = null

    @Volatile
    private var patchedStamp: Long = 0L

    private class Entry(
        val name: String,
        val offset: Int,
        val size: Int,
        val compressed: Boolean,
        val encrypted: Boolean,
        val stored: ByteArray
    )

    /**
     * 如果是 multisg / mannounce 包且尚未打过补丁，就地重打包。
     * 幂等：重复调用只做一次轻量标记检查。失败时返回 false，绝不破坏原包。
     */
    @Synchronized
    fun patchIfNeeded(file: File): Boolean {
        val kind = when {
            file.absolutePath.contains("com.tencent.multimsg") -> Kind.MULTIMSG
            file.absolutePath.contains("com.tencent.mannounce") -> Kind.MANNOUNCE
            else -> return false
        }
        val stamp = file.lastModified() xor file.length()
        if (patchedPath == file.absolutePath && patchedStamp == stamp) return true
        val ok = try {
            when (kind) {
                Kind.MULTIMSG -> patchInPlace(
                    file, TARGET_ENTRY, MARKER, PATCH_BLOCK_REGEX, ::injectMultimsgPatch
                )
                Kind.MANNOUNCE -> patchInPlace(
                    file,
                    MANNOUNCE_TARGET_ENTRY,
                    MANNOUNCE_MARKER,
                    MANNOUNCE_BLOCK_REGEX,
                    ::injectMannouncePatch
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ark repack failed: ${file.name}", t)
            false
        }
        if (ok) {
            patchedPath = file.absolutePath
            patchedStamp = stamp
        }
        return ok
    }

    private fun patchInPlace(
        file: File,
        targetEntry: String,
        marker: String,
        blockRegex: Regex,
        injector: (String) -> String?
    ): Boolean {
        if (!file.isFile) return false
        val src = file.readBytes()
        if (src.size < 64) return false
        if (String(src, 0, 12, Charsets.US_ASCII) != MAGIC_PREFIX) return false

        val blockSize = readU32(src, 16)
        if (blockSize != 25) return false
        val fileCount = readU32(src, 20)
        val indexSize = readU32(src, 24)
        if (src[28].toInt() and 0xFF != 1) return false

        val indexStart = 16 + 4 + blockSize // = 45
        if (indexStart + indexSize > src.size) return false
        val dataBase = indexStart + indexSize

        val indexPlain = xorWithKey(src, indexStart, indexStart + indexSize)
        val entries = parseIndex(indexPlain, fileCount) ?: return false
        if (entries.none { it.name == targetEntry }) return false

        val baseEntry = entries.first { it.name == targetEntry }
        val baseJs = inflateEntry(src, dataBase, baseEntry) ?: return false
        if (marker in baseJs) {
            Log.i(TAG, "ark app already patched: ${file.name}")
            return true
        }

        val patchedJs = injector(baseJs) ?: return false
        val newStored = deflateAndEncrypt(patchedJs.toByteArray(Charsets.UTF_8))

        // 保持原始记录顺序重建数据区与文件表（offset 相对数据区起点）
        val rebuilt = ArrayList<Entry>(entries.size)
        var runningOffset = 0
        for (entry in entries) {
            val stored = if (entry.name == targetEntry) {
                newStored
            } else {
                val start = dataBase + entry.offset
                if (start < 0 || start + entry.size > src.size) return false
                src.copyOfRange(start, start + entry.size)
            }
            rebuilt.add(
                Entry(entry.name, runningOffset, stored.size, entry.compressed, entry.encrypted, stored)
            )
            runningOffset += stored.size
        }

        val indexBytes = serializeIndex(rebuilt)
        val newIndexSize = indexBytes.size

        val header = src.copyOfRange(0, 45)
        writeU32(header, 24, newIndexSize)

        val out = ByteArrayOutputStream(src.size + 64)
        out.write(header)
        out.write(xorWithKey(indexBytes))
        for (entry in rebuilt) out.write(entry.stored)

        // 保留原包备份，便于出问题时手工恢复（TIM 不会自行发现重打包错误，
        // 因为我们放行了签名校验）。
        val backup = File(file.parentFile, file.name + ".bak")
        if (!backup.exists()) {
            FileOutputStream(backup).use { it.write(src) }
        }

        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(out.toByteArray()) }
            if (tmp.renameTo(file)) {
                Log.i(TAG, "ark app repacked: ${file.name} (${out.size()} bytes)")
                return true
            }
            file.delete()
            if (tmp.renameTo(file)) {
                Log.i(TAG, "ark app repacked: ${file.name} (${out.size()} bytes)")
                return true
            }
            return false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun parseIndex(plain: ByteArray, fileCount: Int): List<Entry>? {
        val result = ArrayList<Entry>(fileCount)
        var off = 0
        for (i in 0 until fileCount) {
            if (off + 4 > plain.size) return null
            val recLen = readU32(plain, off)
            if (recLen < 11 || off + 4 + recLen > plain.size) return null
            val nameLen = recLen - 11
            val name = String(plain, off + 4, nameLen, Charsets.UTF_8)
            if (plain[off + 4 + nameLen] != 0.toByte()) return null
            val dataOffset = readU32(plain, off + 5 + nameLen)
            val dataSize = readU32(plain, off + 9 + nameLen)
            val compressed = plain[off + 13 + nameLen].toInt() and 0xFF == 1
            val encrypted = plain[off + 14 + nameLen].toInt() and 0xFF == 1
            result.add(Entry(name, dataOffset, dataSize, compressed, encrypted, ByteArray(0)))
            off += 4 + recLen
        }
        return if (off == plain.size) result else null
    }

    private fun inflateEntry(src: ByteArray, dataBase: Int, entry: Entry): String? {
        val start = dataBase + entry.offset
        if (start < 0 || start + entry.size > src.size) return null
        var stored = src.copyOfRange(start, start + entry.size)
        if (entry.encrypted) stored = xorWithKey(stored)
        if (entry.compressed) {
            stored = try {
                inflateZlib(stored)
            } catch (t: Throwable) {
                return null
            }
        }
        return String(stored, Charsets.UTF_8)
    }

    private fun injectMultimsgPatch(js: String): String? {
        // 先清掉任何旧版本补丁块（幂等升级，避免 V1/V2 叠加）。
        var current = PATCH_BLOCK_REGEX.replace(js, "")

        val colorAnchor =
            "        // 封装的夜间模式\n        var dark = global.getDarkColorModel(config);"
        if (!current.contains(colorAnchor)) return null
        current = current.replace(colorAnchor, colorAnchor + colorBlock())

        val mouseAnchor = "        console.warn(\"mouseDown\");"
        if (!current.contains(mouseAnchor)) return null
        current = current.replace(mouseAnchor, mouseAnchor + mouseBlock())
        return current
    }

    /**
     * 群公告卡片（com.tencent.mannounce）在主题 2971（莫奈）分支里把标题/正文
     * 文字硬编码成 0xFF03081A / 0xFF878B99，完全不走 token。这里在该分支里
     * 追加按 token（bubble_guest_text_primary/secondary）取色，覆盖硬编码值。
     */
    private fun injectMannouncePatch(js: String): String? {
        var current = MANNOUNCE_BLOCK_REGEX.replace(js, "")
        val anchor = "Console.Log(\"mannounce setBackground ConciseWhite\")"
        if (!current.contains(anchor)) return null
        val block =
            "\n" +
                "                        -- ==== $MANNOUNCE_MARKER: 2971 莫奈主题按 token 上色 ====\n" +
                "                        self.textContentView:SetTextColor(theme.getThemeColorValue(constant.THEME.COLOR_MAIN_TITLE_TEXT, colorConfig))\n" +
                "                        self.textContentView3:SetTextColor(theme.getThemeColorValue(constant.THEME.COLOR_MAIN_TITLE_TEXT, colorConfig))\n" +
                "                        self.confirmText:SetTextColor(theme.getThemeColorValue(constant.THEME.COLOR_MAIN_TITLE_TEXT, colorConfig))\n" +
                "                        if self.titleView ~= nil then\n" +
                "                          self.titleView:SetTextColor(theme.getThemeColorValue(constant.THEME.COLOR_TITLE_TEXT, colorConfig))\n" +
                "                        end\n" +
                "                        -- ==== end $MANNOUNCE_MARKER ====\n"
        return current.replace(anchor, block + anchor)
    }

    private fun colorBlock(): String =
        "\n" +
            "        // ==== $MARKER: 卡片统一用对方气泡配色，自己不单独变色 ====\n" +
            "        var tmToken = config && config.token ? config.token : null;\n" +
            "        var tmGuestBg = tmToken ? tmToken.bubble_guest : null;\n" +
            "        this.tmActive = false;\n" +
            "        if (!this.preViewMode && tmGuestBg) {\n" +
            "          var tmParseColor = function (s) {\n" +
            "            if (!s) return 0xFF000000;\n" +
            "            s = String(s).split(\",\")[0].trim().replace(\"#\", \"\");\n" +
            "            if (s.length === 6) s = \"FF\" + s;\n" +
            "            if (s.length === 8) return parseInt(s, 16);\n" +
            "            return 0xFF000000;\n" +
            "          };\n" +
            "          var tmText = tmToken.bubble_guest_text_primary || \"#CCE9FF\";\n" +
            "          var tmText2 = tmToken.bubble_guest_text_secondary || tmText;\n" +
            "          this.tmActive = true;\n" +
            "          this.refs.bgColor = this.bgColor;\n" +
            "          this.bgColor.SetValue(tmParseColor(tmGuestBg));\n" +
            "          this.separatorBgColor.SetValue(0x1AFFFFFF);\n" +
            "          this.titleText.SetTextColor(tmParseColor(tmText));\n" +
            "          for (var tmI = 0; tmI < 4; tmI++) {\n" +
            "            if (this.texts[tmI]) this.texts[tmI].SetTextColor(tmParseColor(tmText2));\n" +
            "          }\n" +
            "          this.preVIewText.SetTextColor(tmParseColor(tmText));\n" +
            "          var tmBottomText = this.view.GetUIObject(\"bottomText\");\n" +
            "          if (tmBottomText) tmBottomText.SetTextColor(tmParseColor(tmText2));\n" +
            "          return;\n" +
            "        }\n" +
            "        // ==== end TimMonetPatch ====\n"

    private fun mouseBlock(): String =
        "\n" +
            "        // ==== $MARKER: 按住不改变颜色 ====\n" +
            "        if (this.tmActive) {\n" +
            "          return;\n" +
            "        }\n" +
            "        // ==== end TimMonetPatch ====\n"

    private fun deflateAndEncrypt(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val buf = ByteArrayOutputStream(data.size / 2 + 64)
            val chunk = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(chunk)
                if (n > 0) buf.write(chunk, 0, n)
            }
            xorWithKey(buf.toByteArray())
        } finally {
            deflater.end()
        }
    }

    private fun inflateZlib(data: ByteArray): ByteArray {
        val inflater = Inflater(false)
        return try {
            inflater.setInput(data)
            val buf = ByteArrayOutputStream(data.size * 4 + 64)
            val chunk = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n > 0) buf.write(chunk, 0, n)
                if (n == 0 &&
                    (inflater.needsInput() || inflater.needsDictionary())
                ) {
                    break
                }
            }
            buf.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun serializeIndex(entries: List<Entry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) {
            val name = entry.name.toByteArray(Charsets.UTF_8)
            out.write(u32Bytes(name.size + 11))
            out.write(name)
            out.write(0)
            out.write(u32Bytes(entry.offset))
            out.write(u32Bytes(entry.size))
            out.write(if (entry.compressed) 1 else 0)
            out.write(if (entry.encrypted) 1 else 0)
        }
        return out.toByteArray()
    }

    private fun xorWithKey(data: ByteArray): ByteArray = xorWithKey(data, 0, data.size)

    private fun xorWithKey(src: ByteArray, start: Int, end: Int): ByteArray {
        val len = end - start
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = (src[start + i].toInt() xor KEY[i % KEY.size].toInt()).toByte()
        }
        return out
    }

    private fun u32Bytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte()
    )

    private fun readU32(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or
            ((src[off + 1].toInt() and 0xFF) shl 8) or
            ((src[off + 2].toInt() and 0xFF) shl 16) or
            ((src[off + 3].toInt() and 0xFF) shl 24)

    private fun writeU32(dst: ByteArray, off: Int, value: Int) {
        dst[off] = (value and 0xFF).toByte()
        dst[off + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
