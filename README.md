<p align="center">
  <img src="https://raw.githubusercontent.com/MarlonPassos-git/Smart-AutoClicker/master/smartautoclicker/src/main/ic_smart_auto_clicker-playstore.png" height="64" alt="Ícone do Klick'r">
</p>

<h1 align="center">Klick'r — Smart AutoClicker</h1>

<p align="center">
  Automação de toques, gestos e cenários Android com reconhecimento de imagem.
</p>

<p align="center">
  <a href="https://github.com/MarlonPassos-git/Smart-AutoClicker/releases">
    <img alt="Baixar APK" src="https://img.shields.io/badge/baixar-APK-2ea44f?style=for-the-badge">
  </a>
  <a href="https://github.com/MarlonPassos-git/Smart-AutoClicker/releases">
    <img alt="Última versão" src="https://img.shields.io/github/v/release/MarlonPassos-git/Smart-AutoClicker?include_prereleases&label=vers%C3%A3o&style=for-the-badge">
  </a>
</p>

Este repositório mantém uma versão personalizada do
[Klick'r](https://github.com/Nain57/Smart-AutoClicker), antigo Smart AutoClicker.
Ela preserva os modos de automação por imagem e por sequência de ações, além de
adicionar recursos desenvolvidos para cenários mais complexos.

## Principais recursos

- **Reconhecimento por várias imagens:** uma condição pode aceitar referências visuais alternativas do mesmo alvo.
- **Textos alternativos:** uma condição de texto pode reconhecer diferentes palavras ou frases.
- **Toque em área:** define uma região poligonal e escolhe um ponto válido dentro dela durante a execução.
- **Zoom configurável:** executa gestos de aproximar ou afastar nos modos inteligente e regular.
- **Automação avançada:** combina imagens, textos, temporizadores, contadores, intents e controle de fluxo.
- **Cenários experimentais para Clash of Clans:** inclui backups iniciais para a vila principal e a Base do Construtor.
- **Aplicativo de desenvolvimento isolado:** compilações de teste usam o nome `Klick´r dev` e podem coexistir com a versão oficial.

Consulte o [histórico de mudanças](CHANGELOG.md) para ver detalhes de cada
versão.

## Instalação

1. Abra a página de [Releases](https://github.com/MarlonPassos-git/Smart-AutoClicker/releases).
2. Entre na versão desejada e baixe o APK compatível:
   - `arm64-v8a`: opção indicada para a maioria dos celulares Android atuais;
   - `armeabi-v7a`: aparelhos ARM de 32 bits;
   - `x86` ou `x86_64`: principalmente emuladores;
   - `universal`: opção maior, compatível com todas as arquiteturas incluídas.
3. Confira o arquivo `SHA256SUMS.txt` se quiser validar a integridade do download.
4. Autorize a instalação de aplicativos desconhecidos quando o Android solicitar.

Versões beta podem usar assinatura de desenvolvimento. Nesse caso, o APK é
instalado como `Klick´r dev`, separado de instalações oficiais do Klick'r.

## Desenvolvimento

Requisitos: JDK 21, Android SDK e o Gradle Wrapper incluído no repositório.

```bash
./gradlew assembleFDroidDebug
./gradlew testFDroidDebugUnitTest
```

Para instalar automaticamente o APK correto em um emulador ou aparelho
conectado:

```bash
mise run install-emulator
```

O processo de publicação está documentado em
[Como publicar uma versão](docs/RELEASES.md).

## Projeto original e licença

Klick'r é software livre distribuído sob a
[GNU General Public License v3.0](LICENSE). O desenvolvimento original e as
versões oficiais para lojas estão em
[Nain57/Smart-AutoClicker](https://github.com/Nain57/Smart-AutoClicker).

## Links

- [Baixar versões deste repositório](https://github.com/MarlonPassos-git/Smart-AutoClicker/releases)
- [Relatar um problema](https://github.com/MarlonPassos-git/Smart-AutoClicker/issues/new?template=bug_report.yml)
- [Consultar mudanças da versão](CHANGELOG.md)
- [Acessar o projeto original](https://github.com/Nain57/Smart-AutoClicker)
