# Which APK should I install?

Este guia explica qual versão e qual arquivo APK escolher nas
[Releases](https://github.com/MarlonPassos-git/Smart-AutoClicker/releases).

## Quick recommendation

Para a maioria dos celulares Android atuais, baixe o arquivo que contém
`arm64-v8a`. Se não souber a arquitetura do aparelho, use `universal`: ele é
maior, mas reúne todas as arquiteturas disponíveis.

Prefira uma versão estável para uso diário. Instale uma versão marcada como
**Pre-release** apenas para testar recursos novos e aceite a possibilidade de
encontrar falhas.

## CPU architecture

| Nome no arquivo | Indicado para |
| --- | --- |
| `arm64-v8a` | Maioria dos celulares e tablets Android atuais |
| `armeabi-v7a` | Aparelhos ARM antigos de 32 bits |
| `x86_64` | Maioria dos emuladores Android em computadores |
| `x86` | Emuladores ou aparelhos x86 antigos de 32 bits |
| `universal` | Alternativa quando a arquitetura é desconhecida |

Um APK de arquitetura incorreta não será instalado. Nesse caso, use
`universal` ou consulte um aplicativo de informações do sistema para confirmar
a ABI do aparelho.

## Build type and signature

Arquivos terminados em `-debug.apk` são versões de desenvolvimento. Eles usam o
nome `Klick´r dev` e o pacote `com.buzbuz.smartautoclicker.debug`, por isso podem
coexistir com o Klick'r oficial. Dados e cenários não são compartilhados
automaticamente entre os dois aplicativos.

Arquivos de release assinados podem atualizar somente uma instalação que use a
mesma chave e o mesmo pacote. Se o Android informar assinatura incompatível,
faça backup dos cenários antes de desinstalar a versão anterior.

## Installation

1. Baixe o APK escolhido na seção **Assets** da release.
2. Abra o arquivo no aparelho.
3. Autorize a instalação por essa fonte quando o Android solicitar.
4. Conclua a instalação e abra o Klick'r.
5. Conceda apenas as permissões necessárias para o cenário que será executado.

## Integrity verification

Cada release inclui `SHA256SUMS.txt`. Compare o hash do APK baixado:

```bash
sha256sum Klickr-*.apk
```

No PowerShell:

```powershell
Get-FileHash .\Klickr-*.apk -Algorithm SHA256
```

O resultado deve ser idêntico ao valor correspondente em `SHA256SUMS.txt`.

## FAQ

### Which APK should most people install?

`arm64-v8a`. Use `universal` quando não souber a arquitetura.

### Can the debug version update the official app?

Não. `Klick´r dev` é instalado separadamente e mantém seus próprios dados.

### Can I install a pre-release for daily use?

É possível, mas não é recomendado. Pré-releases existem para validação de
recursos novos e podem conter regressões.

### What should I do before changing distributions?

Exporte um backup dos cenários. Instalações com pacote ou assinatura diferentes
não compartilham dados automaticamente.
