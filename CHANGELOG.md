# Changelog

Este arquivo registra mudanças relevantes da versão personalizada do Klick'r.
O formato segue o [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/)
e as versões seguem o [Versionamento Semântico](https://semver.org/lang/pt-BR/).

## [Unreleased]

## [4.0.0-beta05] - 2026-07-24

### Added

- Adicionada ação de zoom configurável para aproximar ou afastar em cenários inteligentes e regulares.
- Adicionada ação de toque em área poligonal, com escolha segura de um ponto dentro da região definida.
- Condições de imagem agora aceitam várias referências para reconhecer variações visuais do mesmo alvo.
- Condições de texto agora aceitam várias alternativas de palavras ou frases.
- Incluído um backup experimental de cenário de coleta para a vila principal do Clash of Clans.
- Incluído um backup experimental de cenário de coleta para a Base do Construtor.
- Adicionadas imagens de referência e documentação de pesquisa usadas na criação desses cenários.
- Adicionadas tarefas para compilar e instalar automaticamente o APK compatível com o aparelho conectado.
- Adicionados testes de regressão para zoom, toque em área, textos alternativos, imagens alternativas, migrações e fluxos de edição.

### Changed

- Compilações de desenvolvimento agora aparecem como `Klick´r dev` e usam um identificador separado da versão oficial.
- A geração FDroid produz APK universal e APKs específicos para `armeabi-v7a`, `arm64-v8a`, `x86` e `x86_64`.
- Adicionada migração do banco de dados para armazenar várias referências de imagem sem perder cenários existentes.
- Atualizados backup e restauração para preservar as novas referências.

### Screenshots

As telas abaixo foram registradas no emulador com a versão desta release.

<p>
  <img src="https://raw.githubusercontent.com/MarlonPassos-git/Smart-AutoClicker/master/docs/assets/releases/4.0.0-beta05/action-types.png" width="260" alt="Lista de ações com clique em área e zoom">
  <img src="https://raw.githubusercontent.com/MarlonPassos-git/Smart-AutoClicker/master/docs/assets/releases/4.0.0-beta05/area-click-polygon.png" width="260" alt="Polígono configurado para clique em área">
  <img src="https://raw.githubusercontent.com/MarlonPassos-git/Smart-AutoClicker/master/docs/assets/releases/4.0.0-beta05/reference-image-source.png" width="260" alt="Opções de captura e importação de imagem de referência">
  <img src="https://raw.githubusercontent.com/MarlonPassos-git/Smart-AutoClicker/master/docs/assets/releases/4.0.0-beta05/zoom-action.png" width="260" alt="Ação de zoom configurada">
</p>

[Unreleased]: https://github.com/MarlonPassos-git/Smart-AutoClicker/compare/4.0.0-beta05...HEAD
[4.0.0-beta05]: https://github.com/MarlonPassos-git/Smart-AutoClicker/compare/45545d23...4.0.0-beta05
