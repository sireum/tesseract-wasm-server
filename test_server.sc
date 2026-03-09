#!/usr/bin/env -S scala-cli shebang
//> using scala 2.13
//> using jvm graalvm-community:25.0.2
//> using javaOpt --enable-native-access=ALL-UNNAMED
//> using dep org.graalvm.polyglot:polyglot:25.0.2
//> using dep org.graalvm.wasm:wasm-language:25.0.2
//> using dep org.graalvm.truffle:truffle-runtime:25.0.2
// Test tesseract_server via length-prefixed stdin/stdout protocol.
//
// Usage:
//     scala-cli test_server.sc -- [binary] [--graalvm]
//
// binary defaults to tesseract_server_graal.wasm.
// Without --graalvm: runs via wasmtime subprocess.
// With --graalvm: runs in-process via GraalVM Polyglot/GraalWasm (interpreter mode).

import java.awt.{Color, Font, Graphics2D, RenderingHints}
import java.awt.image.BufferedImage
import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.locks.ReentrantLock
import javax.imageio.ImageIO

// --- Protocol helpers ---

def writeU32(os: OutputStream, v: Int): Unit = {
  os.write((v >> 24) & 0xFF)
  os.write((v >> 16) & 0xFF)
  os.write((v >> 8) & 0xFF)
  os.write(v & 0xFF)
  os.flush()
}

def readU32(is: InputStream): Int = {
  val buf = new Array[Byte](4)
  var n = 0
  while (n < 4) {
    val r = is.read(buf, n, 4 - n)
    if (r < 0) return -1
    n += r
  }
  ((buf(0) & 0xFF) << 24) | ((buf(1) & 0xFF) << 16) |
    ((buf(2) & 0xFF) << 8) | (buf(3) & 0xFF)
}

def recvResponse(is: InputStream): Option[String] = {
  val len = readU32(is)
  if (len < 0) return None
  if (len == 0) return Some("")
  val buf = new Array[Byte](len)
  var n = 0
  while (n < len) {
    val r = is.read(buf, n, len - n)
    if (r < 0) return Some(new String(buf, 0, n, StandardCharsets.UTF_8))
    n += r
  }
  Some(new String(buf, StandardCharsets.UTF_8))
}

// --- Image generation helpers ---

/** Render text onto a white PNG image and return PNG bytes. */
def textToPng(text: String, fontSize: Int = 36, width: Int = 400, height: Int = 80): Array[Byte] = {
  val img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
  val g: Graphics2D = img.createGraphics()
  g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
  g.setColor(Color.WHITE)
  g.fillRect(0, 0, width, height)
  g.setColor(Color.BLACK)
  g.setFont(new Font("SansSerif", Font.PLAIN, fontSize))
  val fm = g.getFontMetrics
  g.drawString(text, 10, fm.getAscent + 10)
  g.dispose()
  val baos = new ByteArrayOutputStream()
  ImageIO.write(img, "png", baos)
  baos.toByteArray
}

/** Render multiple lines of text onto a BMP. */
def multiLineToPng(lines: Seq[String], fontSize: Int = 28): Array[Byte] = {
  val lineHeight = (fontSize * 1.5).toInt
  val width = 600
  val height = lineHeight * lines.size + 40
  val img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
  val g: Graphics2D = img.createGraphics()
  g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
  g.setColor(Color.WHITE)
  g.fillRect(0, 0, width, height)
  g.setColor(Color.BLACK)
  g.setFont(new Font("SansSerif", Font.PLAIN, fontSize))
  val fm = g.getFontMetrics
  for ((line, i) <- lines.zipWithIndex) {
    g.drawString(line, 10, fm.getAscent + 20 + i * lineHeight)
  }
  g.dispose()
  val baos = new ByteArrayOutputStream()
  ImageIO.write(img, "png", baos)
  baos.toByteArray
}

// --- Server abstraction ---

trait Server {
  def outputStream: OutputStream
  def inputStream: InputStream
  def shutdown(): Unit
}

class WasmtimeServer(binary: String) extends Server {
  private val cmd = Seq("wasmtime", "run", binary)
  println(s"  Starting: ${cmd.mkString(" ")}")
  private val proc = new ProcessBuilder(cmd: _*)
    .redirectError(ProcessBuilder.Redirect.PIPE)
    .start()

  def outputStream: OutputStream = proc.getOutputStream
  def inputStream: InputStream = proc.getInputStream

  def shutdown(): Unit = {
    writeU32(proc.getOutputStream, 0)
    proc.getOutputStream.flush()
    proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
    val baos = new ByteArrayOutputStream()
    val buf = new Array[Byte](1024)
    val es = proc.getErrorStream
    while (es.available() > 0) {
      val n = es.read(buf)
      if (n > 0) baos.write(buf, 0, n)
    }
    val stderr = new String(baos.toByteArray, StandardCharsets.UTF_8).trim
    if (stderr.nonEmpty) println(s"  Stderr: ${stderr.take(500)}")
  }
}

/** Ring buffer with ReentrantLock + Condition (avoids PipedInputStream 1-second polling). */
class FastPipe(capacity: Int = 4 * 1024 * 1024) {
  private val buf = new Array[Byte](capacity)
  private var readPos = 0
  private var writePos = 0
  private var count = 0
  private var closed = false
  private val lock = new ReentrantLock()
  private val notEmpty = lock.newCondition()
  private val notFull = lock.newCondition()

  val inputStream: InputStream = new InputStream {
    override def read(): Int = {
      lock.lock()
      try {
        while (count == 0 && !closed) notEmpty.await()
        if (count == 0) return -1
        val b = buf(readPos) & 0xFF
        readPos = (readPos + 1) % capacity
        count -= 1
        notFull.signal()
        b
      } finally lock.unlock()
    }
    override def read(b: Array[Byte], off: Int, len: Int): Int = {
      if (len == 0) return 0
      lock.lock()
      try {
        while (count == 0 && !closed) notEmpty.await()
        if (count == 0) return -1
        val n = math.min(len, count)
        var i = 0
        while (i < n) {
          b(off + i) = buf(readPos)
          readPos = (readPos + 1) % capacity
          i += 1
        }
        count -= n
        notFull.signal()
        n
      } finally lock.unlock()
    }
  }

  val outputStream: OutputStream = new OutputStream {
    override def write(b: Int): Unit = {
      lock.lock()
      try {
        while (count == capacity && !closed) notFull.await()
        if (closed) throw new java.io.IOException("Pipe closed")
        buf(writePos) = b.toByte
        writePos = (writePos + 1) % capacity
        count += 1
        notEmpty.signal()
      } finally lock.unlock()
    }
    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      if (len == 0) return
      lock.lock()
      try {
        var written = 0
        while (written < len) {
          while (count == capacity && !closed) notFull.await()
          if (closed) throw new java.io.IOException("Pipe closed")
          val space = capacity - count
          val n = math.min(len - written, space)
          var i = 0
          while (i < n) {
            buf(writePos) = b(off + written + i)
            writePos = (writePos + 1) % capacity
            i += 1
          }
          count += n
          written += n
          notEmpty.signal()
        }
      } finally lock.unlock()
    }
    override def flush(): Unit = {}
  }

  def close(): Unit = {
    lock.lock()
    try {
      closed = true
      notEmpty.signalAll()
      notFull.signalAll()
    } finally lock.unlock()
  }
}

class GraalVmServer(binary: String) extends Server {
  private val stdinPipe = new FastPipe()
  private val stdoutPipe = new FastPipe()
  @volatile private var alive = false
  @volatile private var error: String = _

  private val thread = new Thread(new Runnable {
    def run(): Unit = {
      var context: org.graalvm.polyglot.Context = null
      try {
        val wasmBytes = Files.readAllBytes(Paths.get(binary))
        val source = org.graalvm.polyglot.Source.newBuilder(
          "wasm",
          org.graalvm.polyglot.io.ByteSequence.create(wasmBytes),
          "tesseract_server"
        ).build()
        context = org.graalvm.polyglot.Context.newBuilder("wasm")
          .option("wasm.Builtins", "wasi_snapshot_preview1")
          .option("wasm.WasiConstantRandomGet", "true")
          .option("wasm.Threads", "true")
          .option("engine.WarnInterpreterOnly", "false")
          .arguments("wasm", Array("tesseract_server"))
          .in(stdinPipe.inputStream)
          .out(stdoutPipe.outputStream)
          .err(System.err)
          .allowAllAccess(true)
          .build()
        val module = context.eval(source)
        val instance = module.newInstance()
        val exports = instance.getMember("exports")
        val startFn = exports.getMember("_start")
        alive = true
        startFn.executeVoid()
      } catch {
        case e: org.graalvm.polyglot.PolyglotException if e.isExit && e.getExitStatus == 0 => // normal
        case e: org.graalvm.polyglot.PolyglotException if e.isExit =>
          error = s"tesseract exited with code ${e.getExitStatus}"
        case e: Throwable =>
          error = s"Error: ${e.getMessage}"
          e.printStackTrace()
      } finally {
        alive = false
        try { stdoutPipe.close() } catch { case _: Throwable => }
        if (context != null) {
          try { context.close() } catch { case _: Throwable => }
        }
      }
    }
  })
  thread.setDaemon(true)
  thread.setName("tesseract-graalwasm")
  thread.start()

  // Wait for WASM module to load (Tesseract init may take a while)
  private val deadline = System.currentTimeMillis() + 120000
  while (!alive && error == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
  if (!alive && error != null) {
    System.err.println(s"GraalVM server failed to start: $error")
    sys.exit(1)
  }
  if (!alive) {
    System.err.println("GraalVM server timed out during startup (120s)")
    sys.exit(1)
  }
  println("  GraalVM server started successfully")

  def outputStream: OutputStream = stdinPipe.outputStream
  def inputStream: InputStream = stdoutPipe.inputStream

  def shutdown(): Unit = {
    writeU32(stdinPipe.outputStream, 0)
    stdinPipe.outputStream.flush()
    thread.join(30000)
  }
}

// --- Test cases ---

case class TestCase(label: String, pngBytes: Array[Byte], expectedWords: Seq[String])

def runTests(server: Server, tests: Seq[TestCase]): Boolean = {
  var allPass = true
  for (tc <- tests) {
    val t0 = System.nanoTime()
    // Send image
    writeU32(server.outputStream, tc.pngBytes.length)
    server.outputStream.write(tc.pngBytes)
    server.outputStream.flush()
    // Read response
    val result = recvResponse(server.inputStream)
    val elapsed = (System.nanoTime() - t0) / 1000000L
    result match {
      case None =>
        println(s"  FAIL  ${tc.label}: no response")
        return false
      case Some(r) =>
        val rLower = r.toLowerCase
        val found = tc.expectedWords.filter(w => rLower.contains(w.toLowerCase))
        val missing = tc.expectedWords.filterNot(w => rLower.contains(w.toLowerCase))
        val ok = missing.isEmpty
        if (!ok) allPass = false
        val display = r.replace('\n', '|').take(200)
        val status = if (ok) "PASS" else "FAIL"
        println(s"  $status  ${tc.label}  (${elapsed}ms)")
        println(s"         Output: $display")
        if (missing.nonEmpty) println(s"         Missing: ${missing.mkString(", ")}")
    }
  }
  allPass
}

// --- Main ---

val useGraalVm = args.contains("--graalvm")
val positionalArgs = args.filterNot(_.startsWith("--"))
val binary = if (positionalArgs.nonEmpty) positionalArgs(0) else "tesseract_server_graal.wasm"
val engine = if (useGraalVm) "GraalVM Polyglot" else "wasmtime"
var overallPass = true

println(s"Binary: $binary")
println(s"Engine: $engine")

def newServer(): Server =
  if (useGraalVm) new GraalVmServer(binary) else new WasmtimeServer(binary)

// Test 1: Simple single word
println("\n=== Test 1: Single word ===")
locally {
  val server = newServer()
  val tests = Seq(
    TestCase("Hello", textToPng("Hello", fontSize = 48, width = 300, height = 80), Seq("Hello")),
    TestCase("World", textToPng("World", fontSize = 48, width = 300, height = 80), Seq("World"))
  )
  if (!runTests(server, tests)) overallPass = false
  server.shutdown()
}

// Test 2: Multiple words
println("\n=== Test 2: Multiple words ===")
locally {
  val server = newServer()
  val tests = Seq(
    TestCase("Hello World", textToPng("Hello World", fontSize = 48, width = 500, height = 80),
      Seq("Hello", "World")),
    TestCase("Tesseract OCR", textToPng("Tesseract OCR", fontSize = 48, width = 500, height = 80),
      Seq("Tesseract", "OCR"))
  )
  if (!runTests(server, tests)) overallPass = false
  server.shutdown()
}

// Test 3: Multiple lines
println("\n=== Test 3: Multiple lines ===")
locally {
  val server = newServer()
  val tests = Seq(
    TestCase("Three lines",
      multiLineToPng(Seq("The quick brown fox", "jumps over the lazy dog", "ABCDEFGHIJKLMNOP")),
      Seq("quick", "brown", "fox", "jumps", "over", "lazy", "dog"))
  )
  if (!runTests(server, tests)) overallPass = false
  server.shutdown()
}

// Test 4: Bounding box format check
println("\n=== Test 4: Bounding box format ===")
locally {
  val server = newServer()
  val png = textToPng("Test", fontSize = 48, width = 200, height = 80)
  writeU32(server.outputStream, png.length)
  server.outputStream.write(png)
  server.outputStream.flush()
  val result = recvResponse(server.inputStream)
  result match {
    case Some(r) if r.nonEmpty && !r.startsWith("ERROR") =>
      // Each line should be: x1 y1 x2 y2 confidence text
      val lines = r.trim.split('\n')
      val formatOk = lines.forall { line =>
        val parts = line.trim.split(' ')
        parts.length >= 6 && parts.take(5).forall(s => scala.util.Try(s.toInt).isSuccess)
      }
      if (formatOk) {
        println(s"  PASS  Format check: ${lines.length} word(s), format correct")
        for (line <- lines) println(s"         $line")
      } else {
        println(s"  FAIL  Format check: unexpected format")
        println(s"         Output: $r")
        overallPass = false
      }
    case Some(r) =>
      println(s"  FAIL  Format check: error or empty response: $r")
      overallPass = false
    case None =>
      println(s"  FAIL  Format check: no response")
      overallPass = false
  }
  server.shutdown()
}

// Test 5: Sequential queries on same server (reuse)
println("\n=== Test 5: Sequential queries (server reuse) ===")
locally {
  val server = newServer()
  var pass = true
  for (i <- 1 to 3) {
    val word = s"Query$i"
    val png = textToPng(word, fontSize = 48, width = 300, height = 80)
    val t0 = System.nanoTime()
    writeU32(server.outputStream, png.length)
    server.outputStream.write(png)
    server.outputStream.flush()
    val result = recvResponse(server.inputStream)
    val elapsed = (System.nanoTime() - t0) / 1000000L
    result match {
      case Some(r) if r.toLowerCase.contains("query") =>
        println(s"  PASS  $word  (${elapsed}ms)  ->  ${r.trim.replace('\n', '|').take(100)}")
      case Some(r) =>
        println(s"  FAIL  $word  (${elapsed}ms)  ->  ${r.trim.replace('\n', '|').take(100)}")
        pass = false
      case None =>
        println(s"  FAIL  $word: no response")
        pass = false
    }
  }
  if (!pass) overallPass = false
  server.shutdown()
}

println(if (overallPass) "\nAll tests passed!" else "\nSOME TESTS FAILED")
sys.exit(if (overallPass) 0 else 1)
