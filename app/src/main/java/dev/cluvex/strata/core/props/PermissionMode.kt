package dev.cluvex.strata.core.props

object PermissionMode {

    const val SETUID = 0x800
    const val SETGID = 0x400
    const val STICKY = 0x200

    const val OWNER_READ = 0x100
    const val OWNER_WRITE = 0x80
    const val OWNER_EXEC = 0x40
    const val GROUP_READ = 0x20
    const val GROUP_WRITE = 0x10
    const val GROUP_EXEC = 0x8
    const val OTHER_READ = 0x4
    const val OTHER_WRITE = 0x2
    const val OTHER_EXEC = 0x1

    const val PERMISSION_MASK = 0xFFF

    private const val S_IFMT = 0xF000
    private const val S_IFDIR = 0x4000
    private const val S_IFLNK = 0xA000

    fun typeChar(fullMode: Int): Char = when (fullMode and S_IFMT) {
        S_IFDIR -> 'd'
        S_IFLNK -> 'l'
        else -> '-'
    }

    fun toSymbolic(mode: Int, type: Char = '-'): String {
        val m = mode and PERMISSION_MASK
        val sb = StringBuilder(10)
        sb.append(type)
        sb.append(if (m and OWNER_READ != 0) 'r' else '-')
        sb.append(if (m and OWNER_WRITE != 0) 'w' else '-')
        sb.append(execChar(m and OWNER_EXEC != 0, m and SETUID != 0, 's'))
        sb.append(if (m and GROUP_READ != 0) 'r' else '-')
        sb.append(if (m and GROUP_WRITE != 0) 'w' else '-')
        sb.append(execChar(m and GROUP_EXEC != 0, m and SETGID != 0, 's'))
        sb.append(if (m and OTHER_READ != 0) 'r' else '-')
        sb.append(if (m and OTHER_WRITE != 0) 'w' else '-')
        sb.append(execChar(m and OTHER_EXEC != 0, m and STICKY != 0, 't'))
        return sb.toString()
    }

    private fun execChar(exec: Boolean, special: Boolean, letter: Char): Char = when {
        special && exec -> letter
        special -> letter.uppercaseChar()
        exec -> 'x'
        else -> '-'
    }

    fun toOctal(mode: Int): String = String.format(java.util.Locale.US, "%04o", mode and PERMISSION_MASK)

    fun parseOctal(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 4 || t.any { it !in '0'..'7' }) return null
        return t.toInt(8)
    }

    fun parseSymbolic(text: String): Int? {
        val s = when (text.length) {
            10 -> text.substring(1)
            9 -> text
            else -> return null
        }
        var mode = 0
        fun rw(c: Char, expected: Char, bit: Int): Boolean = when (c) {
            expected -> { mode = mode or bit; true }
            '-' -> true
            else -> false
        }
        fun ex(c: Char, execBit: Int, specialBit: Int, letter: Char): Boolean = when (c) {
            'x' -> { mode = mode or execBit; true }
            letter -> { mode = mode or execBit or specialBit; true }
            letter.uppercaseChar() -> { mode = mode or specialBit; true }
            '-' -> true
            else -> false
        }
        val ok = rw(s[0], 'r', OWNER_READ) && rw(s[1], 'w', OWNER_WRITE) && ex(s[2], OWNER_EXEC, SETUID, 's') &&
            rw(s[3], 'r', GROUP_READ) && rw(s[4], 'w', GROUP_WRITE) && ex(s[5], GROUP_EXEC, SETGID, 's') &&
            rw(s[6], 'r', OTHER_READ) && rw(s[7], 'w', OTHER_WRITE) && ex(s[8], OTHER_EXEC, STICKY, 't')
        return if (ok) mode else null
    }

    fun has(mode: Int, bit: Int): Boolean = mode and bit != 0

    fun with(mode: Int, bit: Int, enabled: Boolean): Int = if (enabled) mode or bit else mode and bit.inv()
}
