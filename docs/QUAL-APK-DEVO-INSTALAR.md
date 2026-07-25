# Qual APK devo instalar?

Para a maioria dos celulares e tablets Android atuais, escolha o APK
`arm64-v8a`. Se você não souber a arquitetura do aparelho, escolha
`universal`: o arquivo é maior, mas reúne todas as arquiteturas disponíveis.

## Baixar com recomendação automática

A página abaixo tenta identificar a arquitetura informada pelo navegador,
seleciona o APK correspondente na release mais recente e permite alterar a
opção antes do download:

[Descobrir e baixar o APK recomendado](https://marlonpassos-git.github.io/Smart-AutoClicker/baixar-apk.html)

O navegador não fornece essa informação em todos os aparelhos. Quando a
detecção não é confiável, a página recomenda o APK `universal`.

## Escolher manualmente

| Nome no arquivo | Indicado para |
| --- | --- |
| `arm64-v8a` | Maioria dos celulares e tablets Android atuais |
| `armeabi-v7a` | Aparelhos ARM antigos de 32 bits |
| `x86_64` | Maioria dos emuladores Android em computadores |
| `x86` | Emuladores ou aparelhos x86 antigos de 32 bits |
| `universal` | Alternativa quando a arquitetura é desconhecida |

Um APK de arquitetura incorreta não será instalado. Se isso acontecer, use o
arquivo `universal`.

## Instalar e configurar

Depois do download, siga o
[tutorial completo de instalação e configuração](INSTALACAO-E-CONFIGURACAO.md).
Ele explica as permissões do Android, os bloqueios mais comuns e a importação
de cenários.

