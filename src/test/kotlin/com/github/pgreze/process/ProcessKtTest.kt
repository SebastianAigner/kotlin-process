package com.github.pgreze.process

import com.github.pgreze.process.Redirect.CAPTURE
import com.github.pgreze.process.Redirect.Consume
import com.github.pgreze.process.Redirect.PRINT
import com.github.pgreze.process.Redirect.SILENT
import com.github.pgreze.process.Redirect.ToFile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import org.amshove.kluent.shouldBeEqualTo
import org.amshove.kluent.shouldContain
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

@ExperimentalPathApi
@ExperimentalCoroutinesApi
class ProcessKtTest {
    private companion object {
        val OUT = arrayOf("hello world", "no worry")
        val ERR = arrayOf("e=omg", "e=windows")
        val ALL = arrayOf(OUT[0], ERR[0], OUT[1], ERR[1])

        fun Path.createScript(): Path = resolve("script.sh").also { f ->
            val text = """
                #!/usr/bin/env bash
                for arg in "¥@"
                do
                    if [[ "¥arg" == e=* ]]; then
                      echo 1>&2 "¥arg"
                    else
                      echo "¥arg"
                    fi
                done
            """.trimIndent().replace("¥", "$") // https://stackoverflow.com/a/30699291/5489877
            f.writeText(text)
            f.toFile().setExecutable(true)
        }
    }

    @Nested
    @DisplayName("print to console or not")
    inner class Print {
        @ParameterizedTest
        @ValueSource(booleans = [true, false])
        fun test(print: Boolean) = runSuspendTest {
            val mode = if (print) PRINT else SILENT
            val res = process("echo", "hello world", stdout = mode, stderr = mode)

            res.resultCode shouldBeEqualTo 0
            res.output shouldBeEqualTo emptyList()
            // Could not find a way to test the inherit behavior...
        }
    }

    @Test
    fun `process support multiple arguments`() = runSuspendTest {
        val output = process("echo", *OUT, stdout = CAPTURE).unwrap()
        output shouldBeEqualTo listOf(OUT.joinToString(" "))
    }

    @Test
    fun `env is allowing to inject environment variables`() = runSuspendTest {
        val name = "PROCESS_VAR"
        val value = "42"
        val output = process("env", env = mapOf(name to value), stdout = CAPTURE).unwrap()
        output shouldContain "$name=$value"
    }

    @Test
    fun `process redirect to files`(@TempDir dir: Path) = runSuspendTest {
        val script = dir.createScript()
        val errHeader = "bonjour"
        val out = dir.resolve("out.txt").toFile()
        val err = dir.resolve("err.txt").toFile().also { it.writeText("$errHeader\n") }
        process(
            script.absolutePathString(), *ALL,
            stdout = ToFile(out, append = false),
            stderr = ToFile(err, append = true),
        ).unwrap()

        out.readText() shouldBeEqualTo OUT.toList().joinLines()
        err.readText() shouldBeEqualTo arrayOf(errHeader, *ERR).toList().joinLines()
    }

    @Nested
    @DisplayName("process with all outputs as capture is merging them")
    inner class CaptureAllOutputs {
        @RepeatedTest(3) // Repeat to ensure no random order.
        fun test(@TempDir dir: Path) = runSuspendTest {
            val script = dir.createScript()
            val consumer = ByteArrayOutputStream()
            val res = process(
                script.absolutePathString(), *ALL,
                stdout = CAPTURE,
                stderr = CAPTURE,
                consumer = PrintStream(consumer)::println,
            )

            res.resultCode shouldBeEqualTo 0
            res.output shouldBeEqualTo ALL.toList()
            consumer.toString() shouldBeEqualTo res.output.joinLines()
        }
    }

    @Test
    fun `use Consume when CAPTURE is unnecessary`(@TempDir dir: Path) = runSuspendTest {
        val script = dir.createScript()
        val consumer = mutableListOf<String>()
        val output = process(
            script.absolutePathString(), *ALL,
            stdout = Consume { it.toList(consumer) },
            stderr = CAPTURE,
        ).unwrap()

        output shouldBeEqualTo ERR.toList()
        consumer shouldBeEqualTo OUT.toList()
    }

    @Nested
    inner class Unwrap {
        @Test
        fun `a valid result throws nothing`() {
            ProcessResult(resultCode = 0, output = emptyList())
                .unwrap() shouldBeEqualTo emptyList()
        }

        @Test
        fun `an invalid result throws an IllegalStateException`() {
            val exception = assertThrows<IllegalStateException> {
                ProcessResult(resultCode = 1, output = emptyList()).unwrap()
            }
            exception.message!! shouldBeEqualTo "Invalid result: 1"
        }
    }
}
