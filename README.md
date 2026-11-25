# Syniji Zim converter .MD

Conversor Android profissional e 100% offline de arquivos Kiwix/OpenZIM (`.zim`) para bibliotecas Markdown (`.md`) exclusivamente textuais.

## Arquitetura Modular

- `com.example.zim`: Leitura ZIM com `LibzimArchiveAdapter` (`org.kiwix:libkiwix` / `libzim` oficial via JNI) e `PureKotlinZimArchive` (leitor OpenZIM em Kotlin com suporte a LZMA2/XZ, Zstd e zlib).
- `com.example.filter`: `ContentClassifier` com inspeção de MIME type, assinaturas mágicas binárias e verificação UTF-8 para exclusão absoluta de mídias/binários.
- `com.example.parser`: `HtmlSemanticParser` (HTML5/XHTML via Jsoup) e `PlainTextAndXmlConverter` (TXT, XML e texto selecionável de PDF via PDFBox-Android).
- `com.example.markdown`: `YamlFrontMatter` e `SlugUtils` (sanitização contra *path traversal* e geração de âncoras Unicode).
- `com.example.links`: `LinkResolver` para resolução de links relativos, parâmetros, âncoras, links externos e remoção de destinos de mídia mantendo o texto original.
- `com.example.redirects`: `RedirectManager` para preservação de aliases, resolução de cadeias, detecção de ciclos/autorreferências e arquivos individuais `redirects/*.md`.
- `com.example.metadata` & `com.example.index`: `MetadataManager` e `IndexManager` (`README.md`, `HOME.md`, `INDEX.md`, `METADATA.md`, `indexes/INDEX_BY_*.md`).
- `com.example.validator`: `ConversionValidator` (`MANIFEST.md`, `EXCLUDED_CONTENT.md`, `REDIRECTS.md`, `LINKS.md`, `CONVERSION_REPORT.md`, `WARNINGS.md`, `ERRORS.md`).
- `com.example.export`: `ExportManager` (gravação atômica, Modos A/B/C/D/E, ZIP/ZIP64 somente `.md`, espelhamento SAF).
- `com.example.data` & `com.example.search`: Persistência Room com checkpoints de retomada e pesquisa offline SQLite FTS5 (`unicode61 remove_diacritics 2`) com reconstrução a partir de `.md`.
- `com.example.engine`: `ArchiveInspector` e `ConversionEngine` com processamento em fluxo de memória controlada.
- `com.example.ui`: Interface Jetpack Compose Material 3 com navegador offline e visualizador Markdown integrado.

## Compilação Local e Geração do APK

O projeto inclui toda a configuração Gradle, código-fonte Kotlin/Compose e dependências nativas (`libkiwix`/`libzim`, `zstd`, `xz`, `PDFBox`, `Jsoup`, `Room/FTS5`) para gerar o APK completo:

- No **AI Studio**: utilize a opção de **gerar APK/AAB no menu de configurações** da plataforma.
- Localmente (após baixar o ZIP do projeto ou abrir no Android Studio):

```bash
gradle :app:assembleDebug
gradle :app:assembleRelease
gradle :app:testDebugUnitTest
```

O APK compilado no ambiente local fica disponível em `app/build/outputs/apk/debug/app-debug.apk` (e no container de build em `.build-outputs/app-debug.apk`).

