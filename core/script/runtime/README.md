# Runtime JavaScript

`ScriptRuntime` é o contrato do interpretador. O adaptador QuickJS-KT 1.0.18 cria e fecha uma instância por validação ou execução, usa uma cópia das fontes e resolve somente módulos locais e a API `klickr`.

A fronteira Kotlin/JavaScript transporta strings JSON. `ScreenAutomationHost` valida a API e o frame; `ScriptSession` coordena estados, pausa, revalidação e parada. A integração Android e a persistência ficam em módulos separados.

- [API, pacotes e uso no Android](../../../docs/MODO-SCRIPT.md)
- [Arquitetura, extensão e verificação](../../../docs/DESENVOLVIMENTO-MODO-SCRIPT.md)

Com JDK 21:

```sh
./gradlew :core:script:runtime:testFDroidDebugUnitTest
ANDROID_SERIAL=emulator-5582 ./gradlew :core:script:runtime:connectedFDroidDebugAndroidTest
```

A mesma suíte executa contra JNI do host e do Android, incluindo os arquivos reais dos exemplos, cancelamento ocupado/assíncrono, limites, isolamento, prazos e retomada. Substitua o serial pelo dispositivo dedicado aos testes.
