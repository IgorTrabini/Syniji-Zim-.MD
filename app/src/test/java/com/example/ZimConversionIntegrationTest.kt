package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.engine.ArchiveInspector
import com.example.engine.ConversionEngine
import com.example.export.ExportManager
import com.example.model.ArchivePackageFormat
import com.example.model.ConversionConfig
import com.example.model.ConversionProgress
import com.example.model.ConversionTaskStatus
import com.example.model.ExportMode
import com.example.model.MediaMarkerMode
import com.example.parser.HtmlSemanticParser
import com.example.search.SearchRepository
import com.example.zim.ZimOpener
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZimConversionIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var searchRepo: SearchRepository
    private lateinit var engine: ConversionEngine

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(sDb: SupportSQLiteDatabase) {
                    db.isFts5Available = AppDatabase.ensureFts5Table(sDb)
                }
            })
            .build()
        searchRepo = SearchRepository(db)
        engine = ConversionEngine(context, db, searchRepo)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `full conversion excludes all media preserves semantics links redirects and validates output`() = runBlocking {
        val zimFile = tempFolder.newFile("wiki_completo.zim")
        val builder = ZimBinaryTestBuilder().apply {
            addMetadata("Title", "Enciclopédia Teste")
            addMetadata("Description", "Biblioteca multilíngue com acentuação e mídias")
            addMetadata("Language", "por")
            addMetadata("Publisher", "Syniji Test")
            addMetadata("Creator", "OpenZIM Builder")
            addMetadata("Date", "2026-11-24")

            addArticle(
                namespace = 'A',
                url = "Historia/Brasil.html",
                title = "História do Brasil",
                content = """
                    <html><head><title>História do Brasil</title><script>alert(1)</script><style>body{}</style></head>
                    <body>
                    <h1>História do Brasil</h1>
                    <p>Texto com <strong>negrito</strong>, <em>itálico</em>, <del>tachado</del> e acentuação: ação, coração, Café.</p>
                    <h2 id="segunda-guerra">Participação na Segunda Guerra</h2>
                    <p>Veja também <a href="../Sistema_Operacional.html">Sistema Operacional</a> e <a href="https://exemplo.com">Documentação oficial</a>.</p>
                    <figure>
                      <img src="../I/mapa.png" alt="Mapa do Brasil" />
                      <figcaption>Mapa político do Brasil.</figcaption>
                    </figure>
                    <p><a href="../I/audio.mp3">Ouvir gravação</a></p>
                    <video src="../I/video.mp4" title="Introdução ao assunto"></video>
                    <table><tr><th>Ano</th><th>Evento</th></tr><tr><td>1822</td><td>Independência</td></tr></table>
                    <pre><code class="language-kotlin">fun main() = println("Olá")</code></pre>
                    <math display="inline"><annotation encoding="application/x-tex">E = mc^2</annotation></math>
                    </body></html>
                """.trimIndent()
            )
            addArticle(
                namespace = 'A',
                url = "Sistema_Operacional.html",
                title = "Sistema Operacional",
                content = """
                    <html><body>
                    <h1>Sistema Operacional</h1>
                    <p>Artigo de destino final com referência para <a href="Historia/Brasil.html#segunda-guerra">capítulo histórico</a>.</p>
                    </body></html>
                """.trimIndent()
            )
            addArticle(
                namespace = 'A',
                url = "Notas.txt",
                title = "Notas Simples",
                mimeType = "text/plain",
                content = "Conteúdo em texto puro com Unicode: π ≈ 3.14159 e emojis 📚."
            )
            // Redirect chain: Linux -> GNU_Linux -> Sistema_Operacional
            addRedirect('A', "Linux", "Linux", "A/GNU_Linux")
            addRedirect('A', "GNU_Linux", "GNU/Linux", "A/Sistema_Operacional.html")
            // Cycle & self-reference & media target & missing target
            addRedirect('A', "CicloA", "Ciclo A", "A/CicloB")
            addRedirect('A', "CicloB", "Ciclo B", "A/CicloA")
            addRedirect('A', "Auto", "Auto", "A/Auto")
            addRedirect('A', "FotoAlias", "Foto Alias", "I/mapa.png")
            addRedirect('A', "Quebrado", "Quebrado", "A/Inexistente")

            // Binary media entries that MUST NEVER be exported
            addBinary('I', "mapa.png", "image/png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4))
            addBinary('I', "audio.mp3", "audio/mpeg", byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 0, 1))
            addBinary('I', "video.mp4", "video/mp4", byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
            addBinary('I', "estilo.css", "text/css", "body { color: red; }".toByteArray())
            addBinary('I', "app.js", "application/javascript", "console.log(1);".toByteArray())
            addBinary('I', "vetor.svg", "image/svg+xml", "<svg><circle r='5'/></svg>".toByteArray())

            setMainPage("A/Historia/Brasil.html")
        }
        builder.build(zimFile, compressXz = true)
        val sourceHashBefore = sha256(zimFile)

        val inspection = ArchiveInspector.inspectFile(zimFile)
        assertEquals("Enciclopédia Teste", inspection.metadata.title)
        assertEquals(3, inspection.textualResourceCount)
        assertEquals(6, inspection.nonTextualResourceCount)
        assertEquals(7, inspection.redirectCount)

        val outRoot = tempFolder.newFolder("output_lib")
        val config = ConversionConfig(
            exportMode = ExportMode.ORGANIZED_LIBRARY,
            packageFormat = ArchivePackageFormat.ZIP64
        )
        val progress = engine.convertFromFile(zimFile, inspection, config, outRoot)
        assertEquals("Conversion failed: ${progress.errorMessage}", ConversionTaskStatus.COMPLETED, progress.status)

        val libDir = File(progress.outputDirectoryPath)
        val audit = progress.auditSummary
        assertNotNull(audit)
        assertTrue(audit!!.integrityPassed)
        assertEquals(0, audit.nonMarkdownFilesInOutput)
        assertEquals(3, audit.textualConvertedSuccess)
        assertTrue(audit.excludedMediaCount >= 6)
        assertEquals(2, audit.redirectsResolved) // Linux + GNU_Linux
        assertTrue(audit.redirectChainsResolved >= 1) // Linux -> GNU_Linux -> Sistema_Operacional
        assertTrue(audit.redirectCyclesDetected >= 3) // CicloA, CicloB, Auto
        assertEquals(1, audit.redirectMediaTargets) // FotoAlias

        // 1. Verify NO media/binary/non-.md file exists anywhere in output
        val allOutputFiles = libDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(allOutputFiles.isNotEmpty())
        assertTrue(allOutputFiles.all { it.extension.equals("md", ignoreCase = true) })

        // 2. Verify ZIP contains ONLY .md files
        val zipFile = File(progress.zipFilePath!!)
        assertTrue(zipFile.exists())
        ZipFile(zipFile).use { zf ->
            val zipEntries = zf.entries().toList().filter { !it.isDirectory }
            assertTrue(zipEntries.isNotEmpty())
            assertTrue(zipEntries.all { it.name.endsWith(".md") })
        }

        // 3. Verify article Markdown preserves front matter, semantics, stripped media links, and internal links
        val brasilMd = File(libDir, "articles/Historia/Brasil.md").readText()
        assertTrue(brasilMd.contains("source_path: \"A/Historia/Brasil.html\""))
        assertTrue(brasilMd.contains("**negrito**"))
        assertTrue(brasilMd.contains("*itálico*"))
        assertTrue(brasilMd.contains("~~tachado~~"))
        assertTrue(brasilMd.contains("> [Imagem não exportada: Mapa do Brasil]"))
        assertTrue(brasilMd.contains("> Legenda: Mapa político do Brasil."))
        assertTrue(brasilMd.contains("Ouvir gravação [áudio não exportado]"))
        assertTrue(brasilMd.contains("[Sistema Operacional](../Sistema_Operacional.md)"))
        assertTrue(brasilMd.contains("[Documentação oficial](https://exemplo.com)"))
        assertTrue(brasilMd.contains("| Ano | Evento |"))
        assertTrue(brasilMd.contains("```kotlin"))
        assertTrue(brasilMd.contains("\$E = mc^2\$"))
        assertFalse(brasilMd.contains("alert(1)"))

        // 4. Verify redirect chain file Linux.md points to final article
        val linuxRedirectMd = File(libDir, "redirects/Linux.md").readText()
        assertTrue(linuxRedirectMd.contains("redirect_target: \"../articles/Sistema_Operacional.md\""))
        assertTrue(linuxRedirectMd.contains("[Acessar o artigo original: Sistema Operacional](../articles/Sistema_Operacional.md)"))

        // 5. Verify offline search finds articles by accent-insensitive query and alias
        val searchHits = searchRepo.search("coracao", taskId = progress.taskId)
        assertTrue(searchHits.any { it.title == "História do Brasil" })
        val aliasHits = searchRepo.search("Linux", taskId = progress.taskId)
        assertTrue(aliasHits.isNotEmpty())

        // 6. Verify source ZIM file was untouched
        assertEquals(sourceHashBefore, sha256(zimFile))
    }

    @Test
    fun `thousand articles conversion single file mode and resume without duplication`() = runBlocking {
        val zimFile = tempFolder.newFile("mil_artigos.zim")
        val builder = ZimBinaryTestBuilder().apply {
            addMetadata("Title", "Mil Artigos")
            addMetadata("Language", "por")
            for (i in 1..1050) {
                val cat = "cat_${(i % 5) + 1}"
                addArticle(
                    namespace = 'C',
                    url = "$cat/artigo_$i.html",
                    title = "Artigo $i",
                    content = "<h1>Artigo $i</h1><p>Conteúdo textual número $i sobre conhecimento offline.</p>"
                )
            }
            // Duplicate title collision test
            addArticle('C', "cat_1/duplicado.html", "Título Repetido", content = "<h1>Título Repetido</h1><p>Primeiro</p>")
            addArticle('C', "cat_1/duplicado.htm", "Título Repetido", content = "<h1>Título Repetido</h1><p>Segundo</p>")
        }
        builder.build(zimFile, compressXz = false)

        val inspection = ArchiveInspector.inspectFile(zimFile)
        assertEquals(1052, inspection.textualResourceCount)

        // Test Mode B (Single Markdown file) + remove media markers
        val outSingle = tempFolder.newFolder("out_single")
        val singleCfg = ConversionConfig(
            exportMode = ExportMode.SINGLE_FILE,
            mediaMarkerMode = MediaMarkerMode.REMOVE_COMPLETELY
        )
        val singleRes = engine.convertFromFile(zimFile, inspection, singleCfg, outSingle)
        assertEquals("Single conversion failed: ${singleRes.errorMessage}", ConversionTaskStatus.COMPLETED, singleRes.status)
        val singleDir = File(singleRes.outputDirectoryPath)
        val singleMdFile = File(singleDir, "Mil_Artigos.md")
        assertTrue(singleMdFile.exists())
        val singleText = singleMdFile.readText()
        assertTrue(singleText.contains("### Artigo 1050"))
        assertFalse(File(singleDir, "articles").exists())

        // Test Resume: run Mode A, verify checkpoints, and re-run with resumeTaskId without duplicating files
        val outResume = tempFolder.newFolder("out_resume")
        val modeACfg = ConversionConfig(exportMode = ExportMode.ORGANIZED_LIBRARY)
        val firstRun = engine.convertFromFile(zimFile, inspection, modeACfg, outResume)
        val libDir = File(firstRun.outputDirectoryPath)
        val countBefore = libDir.walkTopDown().count { it.isFile }

        val resumedRun = engine.convertFromFile(zimFile, inspection, modeACfg, outResume, resumeTaskId = firstRun.taskId)
        assertEquals(ConversionTaskStatus.COMPLETED, resumedRun.status)
        val countAfter = libDir.walkTopDown().count { it.isFile }
        assertEquals(countBefore, countAfter)

        // Rebuild FTS5 index directly from exported Markdown folder
        val rebuilt = searchRepo.rebuildIndexFromMarkdownDirectory(resumedRun.taskId, libDir)
        assertTrue(rebuilt >= 1052)
    }

    @Test
    fun `corrupted zim cluster fails gracefully without crashing`() = runBlocking {
        val zimFile = tempFolder.newFile("corrompido.zim")
        val builder = ZimBinaryTestBuilder().apply {
            addMetadata("Title", "Teste Corrupção")
            addArticle('A', "ok.html", "Artigo OK", content = "<h1>OK</h1><p>Texto válido.</p>")
        }
        builder.build(zimFile, compressXz = false)

        // Truncate trailing bytes slightly or verify opening invalid magic throws descriptive error
        val invalidFile = tempFolder.newFile("invalido.zim")
        invalidFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        var threw = false
        try {
            ZimOpener.openFromFile(invalidFile).close()
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `parallel conversion emits live progress updates Mode B remaps INDEX anchors and FTS5 skips report files`() = runBlocking {
        val zimFile = tempFolder.newFile("paralelo_modo_b.zim")
        val builder = ZimBinaryTestBuilder().apply {
            addMetadata("Title", "Atlas Paralelo")
            addMetadata("Language", "por")
            addArticle(
                namespace = 'A',
                url = "Origem.html",
                title = "Origem",
                content = """
                    <html><body>
                    <h1>Origem</h1>
                    <p>Veja o <a href="Destino_Final.html">Destino Final</a> para detalhes.</p>
                    </body></html>
                """.trimIndent()
            )
            addArticle(
                namespace = 'A',
                url = "Destino_Final.html",
                title = "Destino Final",
                content = "<html><body><h1>Destino Final</h1><p>Conteúdo alvo exclusivo de pesquisa.</p></body></html>"
            )
            for (i in 1..20) {
                addArticle(
                    namespace = 'A',
                    url = "Capitulo_$i.html",
                    title = "Capítulo $i",
                    content = "<html><body><h1>Capítulo $i</h1><p>Parágrafo único do capítulo $i.</p></body></html>"
                )
            }
        }
        builder.build(zimFile, compressXz = false)

        val inspection = ArchiveInspector.inspectFile(zimFile)
        val outDir = tempFolder.newFolder("out_modo_b")
        val emissions = mutableListOf<ConversionProgress>()
        val collectJob = launch {
            engine.progress.collect { emissions.add(it) }
        }

        val cfg = ConversionConfig(
            exportMode = ExportMode.SINGLE_FILE,
            parallelismWorkers = 4,
            ioBufferSizeKb = 32,
            progressUpdateIntervalMs = 0L
        )
        val finalProgress = engine.convertFromFile(zimFile, inspection, cfg, outDir)
        collectJob.cancel()

        assertEquals("Parallel conversion failed: ${finalProgress.errorMessage}", ConversionTaskStatus.COMPLETED, finalProgress.status)
        assertTrue(emissions.any { it.phaseIndex == 2 && it.currentEntryTitle.isNotBlank() })
        assertTrue(emissions.any { it.status == ConversionTaskStatus.COMPLETED && it.progressFraction == 1f })

        val libDir = File(finalProgress.outputDirectoryPath)
        val singleMd = File(libDir, "Atlas_Paralelo.md")
        assertTrue(singleMd.exists())
        val singleBody = singleMd.readText()
        // Mode B link inside Origem must point directly to #destino-final
        assertTrue(singleBody.contains("[Destino Final](#destino-final)"))

        // INDEX.md must point to Atlas_Paralelo.md#destino-final instead of deleted articles/ path
        val indexMd = File(libDir, "INDEX.md").readText()
        assertTrue(indexMd.contains("(Atlas_Paralelo.md#destino-final)"))
        assertFalse(indexMd.contains("(articles/"))

        // Confined file lookup strips fragment cleanly (as used by MainViewModel)
        val resolvedTarget = ExportManager.resolveConfinedFile(libDir, "Atlas_Paralelo.md#destino-final".substringBefore('#'))
        assertTrue(resolvedTarget.exists())

        // Rebuild FTS5 from folder must index the single consolidated MD, NOT report files (LINKS.md, MANIFEST.md, INDEX.md)
        val rebuiltCount = searchRepo.rebuildIndexFromMarkdownDirectory(finalProgress.taskId, libDir)
        assertEquals(1, rebuiltCount)
        val linkReportHits = searchRepo.search("LINKS.md", taskId = finalProgress.taskId)
        assertFalse(linkReportHits.any { it.markdownRelPath.equals("LINKS.md", ignoreCase = true) })
    }

    @Test
    fun `html parser handles nested media links kbd mark q abbr and table br cleanly`() {
        val parser = HtmlSemanticParser(ConversionConfig())
        val html = """
            <html><body>
            <h1>Elementos Semânticos</h1>
            <p>Atalho <kbd>Ctrl+C</kbd>, destaque <mark>importante</mark>, citação <q>Conhecimento livre</q> e sigla <abbr title="HyperText Markup Language">HTML</abbr>.</p>
            <p><a href="Alvo.html"><img src="../I/thumb.png" alt="Miniatura" /></a></p>
            <table>
              <tr><th>Coluna</th><th>Notas</th></tr>
              <tr><td>Linha 1</td><td>Primeira parte<br/>Segunda parte</td></tr>
            </table>
            </body></html>
        """.trimIndent().toByteArray()

        val parsed = parser.parseAndConvert(html, isXhtml = false, fallbackTitle = "Teste") { href, text ->
            "[$text]($href)"
        }

        assertTrue(parsed.markdownBody.contains("`Ctrl+C`"))
        assertTrue(parsed.markdownBody.contains("**importante**"))
        assertTrue(parsed.markdownBody.contains("\"Conhecimento livre\""))
        assertTrue(parsed.markdownBody.contains("HTML (HyperText Markup Language)"))
        // Nested <img> inside <a> must NOT produce `[> [Imagem não exportada...](Alvo.html)`
        assertTrue(parsed.markdownBody.contains("> [Imagem não exportada: Miniatura]"))
        assertFalse(parsed.markdownBody.contains("[> [Imagem"))
        // Table cell <br> normalized to '; ' so Markdown table row stays intact
        assertTrue(parsed.markdownBody.contains("| Linha 1 | Primeira parte; Segunda parte |"))
    }

    @Test
    fun `split by category mode c removes staging articles and reports exact md file count`() = runBlocking {
        val zimFile = tempFolder.newFile("categorias_modo_c.zim")
        val builder = ZimBinaryTestBuilder().apply {
            addMetadata("Title", "Biblioteca Categorias")
            addMetadata("Language", "por")
            addArticle(
                namespace = 'A',
                url = "Historia/Roma.html",
                title = "Império Romano",
                content = "<html><head><meta name=\"category\" content=\"Historia\"/></head><body><h1>Império Romano</h1><p>Texto sobre Roma.</p></body></html>"
            )
            addArticle(
                namespace = 'A',
                url = "Ciencia/Fisica.html",
                title = "Física Clássica",
                content = "<html><head><meta name=\"category\" content=\"Ciencia\"/></head><body><h1>Física Clássica</h1><p>Leis de Newton.</p></body></html>"
            )
            addBinary('I', "diagrama.png", "image/png", byteArrayOf(1, 2, 3, 4))
        }
        builder.build(zimFile, compressXz = false)

        val inspection = ArchiveInspector.inspectFile(zimFile)
        val outDir = tempFolder.newFolder("out_modo_c")
        val cfg = ConversionConfig(exportMode = ExportMode.SPLIT_BY_CATEGORY)
        val finalProgress = engine.convertFromFile(zimFile, inspection, cfg, outDir)

        assertEquals(ConversionTaskStatus.COMPLETED, finalProgress.status)
        val libDir = File(finalProgress.outputDirectoryPath)
        assertFalse(File(libDir, "articles").exists())

        val actualMdFiles = libDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(actualMdFiles.isNotEmpty())
        assertTrue(actualMdFiles.all { it.extension.equals("md", ignoreCase = true) })
        assertEquals(actualMdFiles.size, finalProgress.markdownFilesCreated)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val r = input.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
