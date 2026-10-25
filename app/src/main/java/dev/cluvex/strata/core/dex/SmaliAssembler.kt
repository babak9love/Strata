package dev.cluvex.strata.core.dex

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.smali.InvalidToken
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import dev.cluvex.strata.R
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.i18n.tr
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.RecognitionException
import org.antlr.runtime.TokenStream
import org.antlr.runtime.tree.CommonTree
import org.antlr.runtime.tree.CommonTreeNodeStream
import org.antlr.runtime.tree.TreeNodeStream
import java.io.File
import java.io.IOException
import java.io.StringReader

data class SmaliError(val source: String, val line: Int, val column: Int, val message: String) {
    override fun toString(): String = buildString {
        if (source.isNotEmpty()) append(source)
        if (line > 0) {
            if (isNotEmpty()) append(':')
            append("line ").append(line)
        }
        if (isNotEmpty()) append(": ")
        append(message)
    }
}

class SmaliSyntaxException(val errors: List<SmaliError>) : IOException(summary(errors)) {
    companion object {
        fun summary(errors: List<SmaliError>): String = when {
            errors.isEmpty() -> tr(R.string.dex_smali_has_errors)
            errors.size == 1 -> errors[0].toString()
            else -> tr(R.string.dex_smali_more_errors, errors[0].toString(), BuildRun.number(errors.size - 1))
        }
    }
}

class SmaliAssembler(val api: Int = DexIndex.API) {

    private val builder = DexBuilder(Opcodes.forApi(api))
    private val errors = ArrayList<SmaliError>()
    private val types = LinkedHashSet<String>()

    val classTypes: Set<String> get() = types
    val errorList: List<SmaliError> get() = errors

    fun add(source: String, text: String): Boolean {
        val before = errors.size
        val lexer = smaliFlexLexer(StringReader(text), api)
        lexer.setSourceFile(File(source))
        lexer.setSuppressErrors(true)
        val tokens = CommonTokenStream(lexer)
        tokens.fill()
        for (t in tokens.tokens) {
            if (t.channel == smaliParser.ERROR_CHANNEL) {
                errors += SmaliError(source, t.line, t.charPositionInLine + 1, (t as? InvalidToken)?.message ?: tr(R.string.dex_unexpected_token, t.text ?: ""))
            }
        }
        val parser = Parser(tokens, source)
        parser.setVerboseErrors(false)
        parser.setAllowOdex(false)
        parser.setApiLevel(api)
        val parsed = try {
            parser.smali_file()
        } catch (e: Exception) {
            errors += SmaliError(source, 0, 0, e.message ?: tr(R.string.dex_cant_read_smali))
            null
        }
        if (parsed == null || errors.size > before || parser.numberOfSyntaxErrors > 0 || lexer.numberOfSyntaxErrors > 0) {
            if (errors.size == before) errors += SmaliError(source, 0, 0, tr(R.string.dex_smali_syntax_error))
            return false
        }
        val nodes = CommonTreeNodeStream(parsed.tree as CommonTree)
        nodes.setTokenStream(tokens)
        val walker = Walker(nodes, source)
        walker.setApiLevel(api)
        walker.setVerboseErrors(false)
        walker.setDexBuilder(builder)
        val def = try {
            walker.smali_file()
        } catch (e: Exception) {
            errors += SmaliError(source, 0, 0, e.message ?: e.javaClass.simpleName)
            null
        }
        if (def == null || errors.size > before || walker.numberOfSyntaxErrors > 0) {
            if (errors.size == before) errors += SmaliError(source, 0, 0, tr(R.string.dex_smali_error))
            return false
        }
        types += def.type
        return true
    }

    fun build(): ByteArray {
        if (errors.isNotEmpty()) throw SmaliSyntaxException(errors.toList())
        if (types.isEmpty()) throw SmaliSyntaxException(listOf(SmaliError("", 0, 0, tr(R.string.dex_no_classes_to_build))))
        val store = MemoryDataStore()
        builder.writeTo(store)
        return store.data
    }

    private inner class Parser(input: TokenStream, private val source: String) : smaliParser(input) {
        override fun displayRecognitionError(tokenNames: Array<out String>?, e: RecognitionException) {
            errors += SmaliError(source, e.line, e.charPositionInLine + 1, getErrorMessage(e, tokenNames))
        }
    }

    private inner class Walker(input: TreeNodeStream, private val source: String) : smaliTreeWalker(input) {
        override fun displayRecognitionError(tokenNames: Array<out String>?, e: RecognitionException) {
            errors += SmaliError(source, e.line, e.charPositionInLine + 1, getErrorMessage(e, tokenNames))
        }
    }

    companion object {
        private val modernOpcodes = listOf("invoke-polymorphic", "invoke-custom", "const-method-handle", "const-method-type")

        fun apiFor(text: String, base: Int = DexIndex.API): Int = if (modernOpcodes.any { text.contains(it) }) maxOf(base, 28) else base

        fun assemble(text: String, api: Int = apiFor(text), source: String = "edit.smali"): ByteArray {
            val asm = SmaliAssembler(api)
            asm.add(source, text)
            return asm.build()
        }
    }
}
