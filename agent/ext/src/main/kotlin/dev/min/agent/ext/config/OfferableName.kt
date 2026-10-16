package dev.min.agent.ext.config

/**
 * skill / command 的名字能不能被提供出来。CC 2.1.299 起：文件或文件夹名里有隐藏、改变方向、看起来像空白的字符，或 `<` `>`，
 * 这个 skill / command 不再提供，改名后才会出现；frontmatter 的 `name:` 不合格则忽略。三处加载器（[SkillLoader]、[CommandLoader]、
 * 插件里的 skills / commands，后者经由前两者的 `readSkill` / `read`）共用这一个判定，别在别处再抄一份。
 *
 * 判定照 CC 2.1.301 二进制里的名字规则（`e1o` 与 `AW`）移植，下面逐条对应：
 * - 长度 1..256（UTF-16 单元）；没有 `< >`、C0/C1 控制符、U+2028/2029。
 * - 没有 Cc / Cf（含 bidi 控制 U+202A–202E、U+2066–2069、U+200E/200F、U+061C，零宽 U+200B–200D、U+2060、U+FEFF）/ 孤立代理 / Co（私用区），
 *   没有 Default_Ignorable_Code_Point（U+115F、U+1160、U+3164、U+FFA0、U+180E、变体选择符 U+FE00–FE0F、标签字符 U+E0000–E0FFF 等）与 U+2800（盲文空白）。
 * - 没有 Zl / Zp、也没有除普通空格 U+0020 以外的 Zs；没有 U+FFFC、U+1D159、U+13441/2、U+16FE4；
 *   名字首尾不能是空格，不能有连续两个空格，冒号（命名空间分隔）前后不能紧挨空格，也不能以冒号开头或结尾。
 * - 组合记号不能「悬空」：开头或跟在非字母 / 数字 / 记号之后、连续 9 个以上、跟在拉丁 / Common 字符后又不是 Inherited 脚本的记号
 *   （叠音标攻击）；点号字母（i、j 等）后接 U+0307；ı / ȷ 后接记号；U+20E3（组合键帽）本身拒绝。
 *
 * 与 CC 的差别（都偏严）：
 * - CC 先把 RGI emoji 序列与印度系 / 阿拉伯 / 僧伽罗文里合法位置的 ZWJ / ZWNJ 换掉再判；这里没有 emoji 序列表，
 *   名字里的 ZWJ（U+200D）、ZWNJ（U+200C）、VS16（U+FE0F）一律拒绝，所以 `❤️` 这类带变体选择符的 emoji 名会被拒。
 * - CC 拒绝 Cn（未分配码位）；JVM / Android 的 Unicode 表版本参差，这里只拒绝「非字符」码位（U+FDD0–FDEF 与每个平面末尾的 xFFFE / xFFFF），
 *   免得旧系统把新字符误杀。
 * - U+FFFD（替换字符）是 Min 额外拒绝的：它说明文件名在某处被错误解码过，名字已经不是用户写的那个。
 */
internal fun isOfferableName(name: String): Boolean {
    if (name.isEmpty() || name.length > NAME_MAX_LENGTH) return false
    if (name.startsWith(' ') || name.endsWith(' ') || name.contains("  ")) return false
    if (name.startsWith(':') || name.endsWith(':') || name.contains(": ") || name.contains(" :")) return false
    val cps = codePointsOf(name)
    for (cp in cps) {
        if (cp == '<'.code || cp == '>'.code || isHiddenOrBlankLike(cp)) return false
    }
    return cps.indices.none { isDanglingMark(cps, it) }
}

/** 名字上限，CC 同为 256（`UB`）；超过的名字它也不提供 */
internal const val NAME_MAX_LENGTH = 256

/** 写进诊断里的规则说明，两处加载器共用，措辞对齐 CC 的 `a name may not hold hidden, direction-changing or blank-like characters, < or >…` */
internal const val NAME_RULE_NOTE =
    "名字里不能有隐藏、改变方向或看起来像空白的字符，也不能有 < 或 >，长度不超过 $NAME_MAX_LENGTH；改名后才会加载"

/** 诊断里引用名字 / 路径用：把隐藏字符与各种空白换成 `\u{十六进制}`，免得诊断本身也看不出问题在哪（CC 的 `dI` 同样转义），并截断到 200 字符 */
internal fun shownName(name: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < name.length && sb.length < 200) {
        val cp = name.codePointAt(i)
        if (isHiddenOrBlankLike(cp)) {
            sb.append("\\u{").append(Integer.toHexString(cp).uppercase()).append('}')
        } else {
            sb.appendCodePoint(cp)
        }
        i += Character.charCount(cp)
    }
    return sb.toString()
}

/** 单个码位是不是「看不见 / 改变方向 / 像空白」。孤立代理也算（codePointAt 会把它原样返回） */
private fun isHiddenOrBlankLike(cp: Int): Boolean {
    if (cp == ' '.code) return false
    when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR,
        -> return true
        else -> Unit
    }
    return isDefaultIgnorable(cp) || isNonCharacter(cp) || cp in EXTRA_BLANK_LIKE
}

/** U+FFFD 之外的几个：盲文空白、对象替换符、乐谱 / 埃及象形文字 / 苗文里的不可见格式字符、键帽组合符；U+FFFD 见类注释 */
private val EXTRA_BLANK_LIKE = setOf(0x2800, 0xFFFC, 0x1D159, 0x13441, 0x13442, 0x16FE4, 0x20E3, 0xFFFD)

private fun isNonCharacter(cp: Int): Boolean = cp in 0xFDD0..0xFDEF || (cp and 0xFFFE) == 0xFFFE

/** Unicode 的 Default_Ignorable_Code_Point（DerivedCoreProperties.txt）；Java 没有这个属性，只能列表 */
private fun isDefaultIgnorable(cp: Int): Boolean = DEFAULT_IGNORABLE.any { cp in it }

private val DEFAULT_IGNORABLE: List<IntRange> = listOf(
    0x00AD..0x00AD, 0x034F..0x034F, 0x061C..0x061C, 0x115F..0x1160, 0x17B4..0x17B5, 0x180B..0x180F,
    0x200B..0x200F, 0x202A..0x202E, 0x2060..0x206F, 0x3164..0x3164, 0xFE00..0xFE0F, 0xFEFF..0xFEFF,
    0xFFA0..0xFFA0, 0xFFF0..0xFFF8, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0000..0xE0FFF,
)

// ---- 组合记号（叠音标）----

/** 第 [at] 个码位是不是一个不该出现的组合记号（规则见类注释）。非记号返回 false，除了「点号字母 / ı / ȷ 后接记号」两条看的是它们自己 */
private fun isDanglingMark(cps: IntArray, at: Int): Boolean {
    val cp = cps[at]
    if (!isMarkLike(cp)) {
        val next = cps.getOrNull(at + 1)
        // ı、ȷ 本身没有点，后面再接记号是在伪造点号字母
        if ((cp == 0x0131 || cp == 0x0237) && next != null && isMark(next)) return true
        if (cp in SOFT_DOTTED) {
            var j = at + 1
            while (j < cps.size && isMark(cps[j])) {
                if (cps[j] == 0x0307) return true
                j++
            }
        }
        return false
    }
    // 开头，或前一个不是字母 / 数字 / 记号：悬空
    val prev = cps.getOrNull(at - 1) ?: return true
    if (!isLetterNumberOrMark(prev)) return true
    // 连续 9 个及以上
    var run = 1
    var k = at - 1
    while (k >= 0 && isMarkLike(cps[k])) { run++; k-- }
    k = at + 1
    while (k < cps.size && isMarkLike(cps[k])) { run++; k++ }
    if (run >= 9) return true
    // 拉丁 / Common 字符（中间可隔若干记号）上叠了不属于 Inherited 脚本的记号，例如拉丁字母上叠泰文声调
    if (Character.UnicodeScript.of(cp) != Character.UnicodeScript.INHERITED) {
        var b = at - 1
        while (b >= 0) {
            val script = Character.UnicodeScript.of(cps[b])
            if (script == Character.UnicodeScript.LATIN || script == Character.UnicodeScript.COMMON) return true
            if (!isMarkLike(cps[b])) break
            b--
        }
    }
    return false
}

/** CC 的 `pe`：\p{M} 加上韩文 Jamo 的中 / 终声区块（它们能当「组合」用） */
private fun isMarkLike(cp: Int): Boolean = isMark(cp) || cp in 0x1160..0x11FF || cp in 0xD7B0..0xD7FF

private fun isMark(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
    else -> false
}

private fun isLetterNumberOrMark(cp: Int): Boolean = isMark(cp) || when (Character.getType(cp).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
    -> true
    else -> false
}

/** Unicode 15 的 Soft_Dotted（Java 没有这个属性）：i、j 和各种带点或形似的变体；后接 U+0307 会叠出两个点 */
private val SOFT_DOTTED: Set<Int> = buildSet {
    addAll(listOf(0x69, 0x6A, 0x12F, 0x249, 0x268, 0x29D, 0x2B2, 0x3F3, 0x456, 0x458, 0x1D62, 0x1D96, 0x1DA4, 0x1DA8, 0x1E2D, 0x1ECB, 0x2071, 0x2148, 0x2149, 0x2C7C, 0x1DF1A, 0x1E04C, 0x1E04D, 0x1E068))
    // 数学字母数字区的 i / j：每套字体两个相邻码位
    for (start in listOf(0x1D422, 0x1D456, 0x1D48A, 0x1D4BE, 0x1D4F2, 0x1D526, 0x1D55A, 0x1D58E, 0x1D5C2, 0x1D5F6, 0x1D62A, 0x1D65E, 0x1D692)) {
        add(start)
        add(start + 1)
    }
}

private fun codePointsOf(s: String): IntArray {
    val out = IntArray(s.codePointCount(0, s.length))
    var i = 0
    var n = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        out[n++] = cp
        i += Character.charCount(cp)
    }
    return out
}
