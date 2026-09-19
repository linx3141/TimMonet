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
    // ⚠️ 幂等判据 = "包里有没有这个字符串"，所以**注入内容一改就必须升版本号**
    // （V3 → V4）：否则设备上已打过旧补丁的 .ark 会直接 return true，永远不会
    // 更新成新内容。升版本号时旧块由 patchInPlace 的 blockRegex 剥掉。
    private const val MARKER = "TimMonetPatchV3"
    private const val TARGET_ENTRY = "baseView.js"
    private const val MANNOUNCE_MARKER = "TimMonetMannouncePatch"
    private const val MANNOUNCE_TARGET_ENTRY = "mannounce.lua"

    /**
     * 结构化消息卡片（`com.tencent.structmsg`）的配色补丁。
     *
     * 它把卡片配色**硬编码**在 `base/theme.lua` 的方案表里
     * （`COLOR_SCHEME_CONCISE_WHITE = { background = 0xFFFFFFFF, ... }`），完全不走
     * QUI token 表 —— 所以深色莫奈下这类卡片一直是它自己的白/灰底。
     * 补丁在文件末尾追加一段包装：`getThemeColorConfig()` 返回前，用宿主注入在
     * 配置里的 `theme.timMonet`（模块按 token 算好的**莫奈色**）覆盖方案表。
     */
    private const val STRUCTMSG_MARKER = "TimMonetStructmsgPatch"
    private const val STRUCTMSG_TARGET_ENTRY = "base/theme.lua"

    /**
     * 小程序卡片（`com.tencent.miniapp_01`）的配色补丁。
     *
     * 卡片视图是 JS 画的，颜色**硬编码**在 JS 里（亮色 `0xFFFFFFFF`/`0xFFF5F6FA`…、
     * 夜间 `0xFF242526`/`0xFF262626`…、品牌蓝 `0xFF0099FF`…），既不读 QUI token
     * 也不看我们的 token 重映射 —— 深色下永远是它自己的灰、亮色下永远是白。
     * 补丁把每个 `.js` entry 里这些字面量换成 `__tm("role", 原值)`：运行时从
     * 宿主注入的 `app.config.theme.timMonet` 取莫奈色，取不到就退回原值。
     */
    private const val MINIAPP_MARKER = "TimMonetMiniappPatch"
    /** 补丁内容版本：**改了下面任何映射/代码就要 +1**（幂等判据是 marker 字符串）。 */
    private const val MINIAPP_PATCH_VERSION = 8

    private val KEY = "20180730104551tm".toByteArray(Charsets.US_ASCII)

    private val PATCH_BLOCK_REGEX =
        Regex("(?s)// ==== TimMonetPatch.*?// ==== end TimMonetPatch ====\\s*")

    private val MANNOUNCE_BLOCK_REGEX = Regex(
        "(?s)-- ==== TimMonetMannouncePatch.*?-- ==== end TimMonetMannouncePatch ====\\s*"
    )

    private val STRUCTMSG_BLOCK_REGEX = Regex(
        "(?s)-- ==== TimMonetStructmsgPatch.*?-- ==== end TimMonetStructmsgPatch ====\\s*"
    )

    private val MINIAPP_BLOCK_REGEX = Regex(
        "(?s)// ==== TimMonetMiniappPatch.*?// ==== end TimMonetMiniappPatch ====\\s*"
    )

    /**
     * `com.tencent.miniapp_01` 的 **XML** 里"硬编码颜色 -> 我们的角色"。
     *
     * ⚠️ 必须和 JS 那张表分开：同一个字面量在两处语义不同 ——
     * XML 的 `0xFFFFFFFF` 是卡片里的**标题条**（比卡片底更亮的一档），
     * JS 的 `0xFFFFFFFF` 是**亮色主题下的卡片底**。混用会把卡片底和标题条对调。
     */
    private val MINIAPP_XML_FILL_ROLES: Map<String, String> = mapOf(
        // 卡片整体底（浅灰）
        "0xFFEAEDF4" to "background",
        "0xFFEBEDF5" to "background",
        "0xFFF5F6FA" to "background",
        "0xFFEEEEF2" to "background",
        // 卡片内的标题条（白，比卡片底亮一档）
        "0xFFFFFFFF" to "backgroundAlt",
        // 品牌蓝竖条
        "0xFF4D94FF" to "brand",
        "0xFF00CAFC" to "brand",
        "0xFF0099FF" to "brand"
    ).mapKeys { it.key.uppercase() }

    /**
     * XML 里 `textcolor=` 的映射。
     *
     * ⚠️ **必须和 `color=` 分开**：`0xFFFFFFFF` 在 `color=` 上是"标题条底"、
     * 在 `textcolor=` 上是**白字**。混在一起会把白字写成卡片底色 → 深色面上
     * 文字直接消失（用户实测："背景正常了但文字被吞了"）。
     */
    private val MINIAPP_XML_TEXT_ROLES: Map<String, String> = mapOf(
        "0xFFFFFFFF" to "title",
        "0xFF03081A" to "title",
        "0xFF222222" to "title",
        "0xFF666666" to "title",
        "0xFF878B99" to "summary",
        "0xFF909094" to "summary",
        "0xFFB2B2B2" to "summary",
        "0xFF999999" to "summary",
        "0xFFCBCED6" to "summary"
    ).mapKeys { it.key.uppercase() }

    /**
     * 小程序卡片 JS 里"硬编码颜色 -> 我们的角色"对照表。
     *
     * ⚠️ 查表前两边都要**统一成大写**（map 键已 `.mapKeys{uppercase}`，查表用
     * `m.value.uppercase()`）：JS 里同一个颜色大小写混用（`0xFF242526` / `0xff878B99`），
     * 只按原样做键会一个都匹配不上 —— 踩过：替换数 0 → transform 原样返回 →
     * 文件被"成功重打包"但内容一点没变（日志还写着 repacked），极难查。
     */
    private val MINIAPP_COLOR_ROLES: Map<String, String> = mapOf(
        // 卡片底（亮色白 / 夜间 #242526、#262626）
        "0xFFFFFFFF" to "background",
        "0xFF242526" to "background",
        "0xFF262626" to "background",
        // 卡片内的次级底（浅灰条/占位底）
        "0xFFF5F6FA" to "backgroundAlt",
        "0xFFEBEDF5" to "backgroundAlt",
        "0xFFEAEDF4" to "backgroundAlt",
        "0xFFEEEEF2" to "backgroundAlt",
        "0xFFF5F6F5" to "backgroundAlt",
        // 主标题/正文 -> onSurface（用户要求："文字要 onSurface"）
        "0xFF03081A" to "title",
        "0xFF222222" to "title",
        "0xFF2E2E2E" to "title",
        // ⚠️ JS 里 `descUIObj`（卡片主标题）夜间用的是 0xFF999999，
        // 不是深灰那两个 —— 它必须走 onSurface；mis-map 成 onSurfaceVariant
        // 会让主标题比正文暗一档（用户实测："染错色了，我要 onSurface 文字"）。
        "0xFF999999" to "title",
        // 次要文字（应用名 "哔哩哔哩"/页脚 "QQ小程序"）才用 onSurfaceVariant
        "0xFF878B99" to "summary",
        "0xFFB2B2B2" to "summary",
        "0xFF909094" to "summary",
        "0xFFCBCED6" to "summary",
        "0xFF616573" to "summary",
        "0xFF666666" to "summary",
        // 品牌蓝（左侧竖条等）
        "0xFF0099FF" to "brand",
        "0xFF4D94FF" to "brand",
        "0xFF00CAFC" to "brand"
    ).mapKeys { it.key.uppercase() }


    private enum class Kind { MULTIMSG, MANNOUNCE, STRUCTMSG, MINIAPP }

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
            file.absolutePath.contains("com.tencent.structmsg") -> Kind.STRUCTMSG
            file.absolutePath.contains("com.tencent.miniapp_01") -> Kind.MINIAPP
            else -> return false
        }
        val stamp = file.lastModified() xor file.length()
        if (patchedPath == file.absolutePath && patchedStamp == stamp) return true
        val ok = try {
            when (kind) {
                Kind.MULTIMSG -> patchInPlace(
                    file,
                    { it == TARGET_ENTRY },
                    MARKER,
                    PATCH_BLOCK_REGEX
                ) { _, js -> injectMultimsgPatch(js) }
                Kind.MANNOUNCE -> patchInPlace(
                    file,
                    { it == MANNOUNCE_TARGET_ENTRY },
                    MANNOUNCE_MARKER,
                    MANNOUNCE_BLOCK_REGEX
                ) { _, lua -> injectMannouncePatch(lua) }
                Kind.STRUCTMSG -> patchInPlace(
                    file,
                    { it == STRUCTMSG_TARGET_ENTRY },
                    STRUCTMSG_MARKER,
                    STRUCTMSG_BLOCK_REGEX
                ) { _, lua -> injectStructmsgPatch(lua) }
                Kind.MINIAPP -> {
                    // 配色指纹进 marker：调色板一变（换壁纸/改设置）指纹就变，
                    // 幂等检查自然失败 → 重打一遍（旧的块由 blockRegex 剥掉）。
                    // XML 里的颜色是**静态**的（Ark 的 XML 不能写表达式），只能把
                    // 当前调色板的值烤进去，所以必须能随调色板重打。
                    val roleColors = miniappRoleColors()
                    val fingerprint = roleColors.values.joinToString("-") {
                        Integer.toHexString(it)
                    }
                    patchInPlace(
                        file,
                        { it.endsWith(".js") || it.endsWith(".xml") },
                        "$MINIAPP_MARKER v$MINIAPP_PATCH_VERSION $fingerprint",
                        MINIAPP_BLOCK_REGEX
                    ) { name, text ->
                        if (name.endsWith(".js")) {
                            injectMiniappPatch(text, roleColors, fingerprint)
                        } else {
                            injectMiniappXml(text, roleColors, fingerprint)
                        }
                    }
                }
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
        selectEntries: (String) -> Boolean,
        marker: String,
        blockRegex: Regex,
        transform: (String, String) -> String?
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
        val targets = entries.filter { selectEntries(it.name) }
        if (targets.isEmpty()) return false

        // 幂等判据：任一目标 entry 里已有 marker 就算打过了。
        // ⚠️ 注入内容一变就要升 marker 版本号（否则设备上已打过旧补丁的包会被跳过），
        // 旧块由 blockRegex 在下面剥掉。
        val originals = HashMap<String, String>()
        for (e in targets) {
            val text = inflateEntry(src, dataBase, e) ?: return false
            if (marker in text) {
                Log.i(TAG, "ark app already patched: ${file.name}")
                return true
            }
            originals[e.name] = text
        }

        val newStoredByName = HashMap<String, ByteArray>()
        for ((name, text) in originals) {
            val stripped = blockRegex.replace(text, "")
            val patched = transform(name, stripped) ?: return false
            newStoredByName[name] = deflateAndEncrypt(patched.toByteArray(Charsets.UTF_8))
        }

        // 保持原始记录顺序重建数据区与文件表（offset 相对数据区起点）
        val rebuilt = ArrayList<Entry>(entries.size)
        var runningOffset = 0
        for (entry in entries) {
            val patchedEntry = newStoredByName[entry.name]
            val stored = if (patchedEntry != null) {
                patchedEntry
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

        // ⚠️ 备份必须**每次刷新**，且替换过程必须可回滚。
        //
        // 历史实现是"如果 .bak 不存在就写一份，然后 file.delete() + rename"：
        // 一旦 `tmp.renameTo(file)` 在删除原文件之后仍然失败（跨挂载点、
        // 目录权限、目标被 mmap 占用等），原包就**永久消失**，而函数只返回
        // false、日志里没有任何异常 —— TIM 之后加载的是一个不存在的 ark。
        // 与 KDoc 承诺的"失败时返回 false，绝不破坏原包"完全相反。
        //
        // 现在：先把当前内容写进 .bak（每次都写，保证内容恰好是"本次改动前"
        // 的版本），rename 失败时可原样回滚。
        val backup = File(file.parentFile, file.name + ".bak")
        try {
            backup.delete()
            FileOutputStream(backup).use { it.write(src) }
        } catch (t: Throwable) {
            Log.w(TAG, "ark backup failed, aborting repack of ${file.name}", t)
            return false
        }

        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { it.write(out.toByteArray()) }
            if (tmp.renameTo(file)) {
                Log.i(TAG, "ark app repacked: ${file.name} (${out.size()} bytes)")
                return true
            }
            // rename 失败：先把原包挪开（保留字节，不删除），再放新包。
            if (!file.renameTo(backup)) {
                Log.w(
                    TAG,
                    "ark replace failed (cannot move original aside): ${file.name}; " +
                        "original untouched, .bak kept"
                )
                return false
            }
            if (tmp.renameTo(file)) {
                Log.i(TAG, "ark app repacked: ${file.name} (${out.size()} bytes)")
                return true
            }
            // 新包放不进去：把原包挪回来，绝不留下"两边都没有"的状态。
            val restored = backup.renameTo(file)
            Log.w(
                TAG,
                "ark replace failed: ${file.name}; original restored=$restored " +
                    (if (!restored) "(recover from ${backup.name})" else "")
            )
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
    /**
     * 给 `com.tencent.structmsg` 的 `base/theme.lua` 追加一段包装：返回前用宿主注入的
     * `theme.timMonet`（模块算好的**莫奈色**）覆盖它内部的配色方案表。
     *
     * 追加在**文件末尾**（不按 anchor 插进函数体）：lua 里这些函数与 `THEME_COLOR_*`
     * 常量都是该模块的全局，末尾重定义 `getThemeColorConfig` 即生效，跨版本最稳；
     * 找不到目标函数就返回 null（放弃），绝不写坏包。
     */
    private fun injectStructmsgPatch(lua: String): String? {
        val current = STRUCTMSG_BLOCK_REGEX.replace(lua, "")
        if (!current.contains("function getThemeColorConfig")) return null
        if (!current.contains("THEME_COLOR_BACKGROUND")) return null
        val block =
            "\n" +
                "-- ==== $STRUCTMSG_MARKER: 用模块注入的莫奈配色覆盖内置方案 ====\n" +
                "do\n" +
                "    local __tmOrigGetThemeColorConfig = getThemeColorConfig\n" +
                "    function getThemeColorConfig(view)\n" +
                "        local cfg = __tmOrigGetThemeColorConfig(view)\n" +
                "        local tm = nil\n" +
                "        local ok, t = pcall(function() return app.getThemeConfig() end)\n" +
                "        if ok and t ~= nil then tm = t[\"timMonet\"] end\n" +
                "        if tm == nil or cfg == nil then return cfg end\n" +
                "        local function __tmSet(key, v)\n" +
                "            if v ~= nil then\n" +
                "                local n = tonumber(v)\n" +
                "                if n ~= nil then cfg[key] = n end\n" +
                "            end\n" +
                "        end\n" +
                "        __tmSet(THEME_COLOR_BACKGROUND, tm[\"background\"])\n" +
                "        __tmSet(THEME_COLOR_TITLE, tm[\"title\"])\n" +
                "        __tmSet(THEME_COLOR_SUMMARY, tm[\"summary\"])\n" +
                "        __tmSet(THEME_COLOR_SOURCE, tm[\"source\"])\n" +
                "        __tmSet(THEME_COLOR_TAG, tm[\"tag\"])\n" +
                "        __tmSet(THEME_COLOR_TAG_BACKGROUND, tm[\"tagBackground\"])\n" +
                "        __tmSet(THEME_COLOR_SEPERATOR, tm[\"separator\"])\n" +
                "        __tmSet(THEME_COLOR_PIC_BORDER, tm[\"picBorder\"])\n" +
                "        return cfg\n" +
                "    end\n" +
                "end\n" +
                "-- ==== end $STRUCTMSG_MARKER ====\n"
        return current + block
    }

    /**
     * 给 `com.tencent.miniapp_01` 的每个 `.js` 打补丁：把硬编码颜色字面量替换成
     * `__tm("role", 原值)` —— 运行时从宿主注入的 `app.config.theme.timMonet` 取
     * 莫奈色；取不到（老宿主/未注入）就退回原来的字面量，行为与打补丁前一致。
     *
     * 只改 JS 不改 XML：这些卡片的颜色最终都由 JS 在 `OnSetValue`/`darkModeAdapt`
     * 里重设（XML 里只是首帧初值），改 JS 就够，也避开 XML 不能写表达式的限制。
     */
    /** 小程序卡片用到的角色 -> 当前调色板的具体颜色。 */
    private fun miniappRoleColors(): Map<String, Int> {
        val scheme = MonetPalette.palette()
        return mapOf(
            "background" to TokenMapper.bgCard(),
            "backgroundAlt" to TokenMapper.inputBg(),
            "title" to scheme.onSurface,
            "summary" to scheme.onSurfaceVariant,
            "brand" to scheme.primary
        )
    }

    /**
     * XML 里的颜色是**静态字面量**（Ark 的 XML 不能写表达式/读 token），所以这里把
     * 当前调色板的值直接烤进去；调色板变化时靠 marker 里的指纹触发重打（见调用点）。
     */
    private fun injectMiniappXml(
        xml: String,
        roleColors: Map<String, Int>,
        fingerprint: String
    ): String? {
        // ⚠️ XML 的改色是**不可逆**的（把字面量换成了我们的颜色），所以补丁块里
        // 必须保存**原始 XML**（base64）：重打（换壁纸/改配色）时先还原原文再重新
        // 上色。否则第二次打补丁时原始字面量已经不存在，替换数 0 → 直接返回原文
        // → 卡片永远停在上一次的配色（踩过：marker 已更新但颜色没变）。
        val blockRegex = Regex("(?s)<!--\\s*TimMonetMiniappPatch.*?-->\\s*")
        val existing = blockRegex.find(xml)?.value
        val original = if (existing != null) {
            val b64 = Regex("orig=([A-Za-z0-9+/=]+)").find(existing)?.groupValues?.get(1)
            val decoded = b64?.let {
                runCatching {
                    String(android.util.Base64.decode(it, android.util.Base64.NO_WRAP), Charsets.UTF_8)
                }.getOrNull()
            }
            decoded ?: xml
        } else {
            xml
        }
        var replaced = 0
        val body = Regex("(textcolor|color)=\"(0x[0-9A-Fa-f]{6,8})\"").replace(original) { m ->
            val attr = m.groupValues[1]
            val literal = m.groupValues[2].uppercase()
            val table = if (attr == "textcolor") {
                MINIAPP_XML_TEXT_ROLES
            } else {
                MINIAPP_XML_FILL_ROLES
            }
            val argb = table[literal]?.let { roleColors[it] }
            if (argb == null) {
                m.value
            } else {
                replaced++
                attr + "=\"0x" + Integer.toHexString(argb).uppercase().padStart(8, '0') + "\""
            }
        }
        if (replaced == 0) return original
        val encoded = android.util.Base64.encodeToString(
            original.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        val header =
            "<!--TimMonetMiniappPatch v$MINIAPP_PATCH_VERSION $fingerprint orig=$encoded-->\n"
        return header + body
    }

    private fun injectMiniappPatch(
        js: String,
        roleColors: Map<String, Int>,
        fingerprint: String
    ): String? {
        val current = MINIAPP_BLOCK_REGEX.replace(js, "")
        var replaced = 0
        val body = Regex("0x[0-9A-Fa-f]{6,8}").replace(current) { m ->
            val role = MINIAPP_COLOR_ROLES[m.value.uppercase()]
            val argb = role?.let { roleColors[it] }
            if (argb == null) {
                m.value
            } else {
                replaced++
                // ⚠️ 直接**烤成当前调色板的颜色**，不做任何运行时查询：
                //  - 试过 `function __tm(...)`（全局辅助函数）：卡片底染上了、
                //    文字全没 —— 典型的"后续语句抛错/取不到值导致渲染中断"；
                //  - 试过就地 IIFE 查 `app.config.theme.timMonet`：运行时那个对象
                //    不一定可见（实测又退回它自己的灰色）。
                // 调色板变化时靠 marker 里的指纹触发重打（TIM 改配色本来就会重启）。
                "0x" + Integer.toHexString(argb).uppercase().padStart(8, '0')
            }
        }
        // ⚠️ 没有任何可替换字面量的 entry 要**原样返回**，不能返回 null：
        // 多 entry 打补丁时 null 表示"这个包放弃"，会让整个补丁失败
        // （踩过：all.js 里没有认识的色值 → 整个 miniapp_01 补丁被放弃）。
        if (replaced == 0) return current
        val header =
            "// ==== $MINIAPP_MARKER v$MINIAPP_PATCH_VERSION $fingerprint: " +
                "硬编码配色 -> 模块注入的莫奈色（就地 IIFE，无全局依赖）====\n"
        return header + body
    }

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
