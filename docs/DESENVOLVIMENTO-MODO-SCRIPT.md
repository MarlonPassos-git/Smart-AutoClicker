# Desenvolvimento do modo Script

O motor textual usa contratos próprios e mantém os tipos do interpretador dentro de `core/script/runtime`. O aplicativo continua com os modos smart e regular; a sessão textual não grava eventos, contadores ou migrações no banco smart.

## Responsabilidades

| Local | Responsabilidade |
| --- | --- |
| `core/script/runtime` | Runtime QuickJS, API JSON versionada, estados, prazos, diagnóstico e coordenação serial. |
| `core/script/packages` | Manifesto, parâmetros, revisões imutáveis, importação/exportação e publicação da calibração. |
| `core/script/android` | Adaptador do detector/gestos, recursos decodificados e execução de um pacote. |
| `feature/script-config` | Biblioteca, rascunho, parâmetros, captura de recursos e controles flutuantes Material. |
| `smartautoclicker/localservice` | Projeção e propriedade dos recursos entre modos, execução e calibração. |
| `examples/scripts` | Regras dos exemplos, perfis e imagens. Regras de Clash ficam aqui. |

`SessionOwnership` mantém a posse entre instâncias de `LocalService`. O adaptador de scripts aguarda o callback/timeout do executor Android em contexto não cancelável e confere o cancelamento antes e depois desse gesto. Assim, a limpeza só libera um ponteiro depois de o executor registrar seu resultado; uma sequência cancelada não inicia o próximo toque. Um gesto longo já despachado pode prolongar o estado de parada. A posse só é liberada depois do término da captura, do detector e dos gestos pendentes. `AutomationSessionCoordinator` serializa chamadas de uma sessão no dispatcher fornecido e rejeita acessos antigos após seu encerramento. A configuração padrão de `PackageScriptExecution` mantém QuickJS e operações Android na mesma thread dedicada, preservando o contrato de `ImageDetector`. O watchdog continua em dispatchers independentes para interromper código JavaScript ocupado.

`ScriptSession` é de uso único. Os estados são preparando, executando, pausando, pausado, revalidando, parando, interrompido, concluído e falhou. Pausar é cooperativo; parar cancela a corrotina e interrompe o interpretador. Encerramento e liberação aguardam operações em andamento em contexto não cancelável.

## Interpretador e limites

QuickJS-KT está fixado em `gradle/libs.versions.toml`, na versão 1.0.18. Testes locais substituem o artefato Android pelo artefato JVM da mesma versão. Instrumentação usa o binário Android. Cada validação ou execução cria um interpretador e o fecha em `finally`.

Na versão escolhida, o prazo nativo de avaliação inclui o tempo aguardado em chamadas Kotlin. O adaptador usa interrupção e sondagem do lock nativo para limitar JavaScript continuamente ocupado, mantendo esperas longas válidas. Os testes cobrem espera superior ao orçamento de código ocupado, loop infinito e loop ocupado com uma promessa de host pendente.

Os limites atuais e o formato público estão no [guia de scripts](MODO-SCRIPT.md). Memória do interpretador, templates e arquivos são limites distintos. A captura é emprestada de `DisplayRecorder`; o adaptador não a recicla. Os templates pertencem à sessão e são liberados depois de o detector parar de usá-los.

Em imagem, `ImageDetector.detectImage` recebe o tamanho esperado do template, não o da tela. O tamanho do template é escalado a partir do perfil de referência. A instrumentação verifica esse contrato com o detector real em duas resoluções e com os recortes do farm.

## Acrescentar uma operação

1. Defina o contrato da API 1 ou decida por outra versão quando a mudança quebrar scripts existentes. Use entradas/saídas JSON e erros com campo, valor inválido e formato esperado.
2. Valide toda a solicitação em `ScreenAutomationHost` antes de executar efeitos. Um resultado reconhecido precisa manter a conferência do frame; uma operação que muda a tela invalida a observação.
3. Acrescente o contrato de integração a `ScreenAutomationBackend` e implemente o adaptador Android com as interfaces existentes. Preserve serialização, thread do detector, cancelamento e liberação de ponteiros.
4. Exponha a função em `api-v1.js`. Não entregue objetos Android/JVM, reflexão, rede ou acesso ao sistema de arquivos para o script.
5. Cubra comportamento e regressões com JavaScript real. Use instrumentação quando JNI, bitmaps, OCR ou gestos Android determinarem o resultado. Atualize o guia público e os exemplos afetados.

Falhas esperadas de frame usam `ScriptApiException` com código `stale_observation`. Outros erros preservam a causa e encerram a execução. Recuperações devem reconhecer uma tela prevista e devolver a decisão ao fluxo principal; não iniciam um segundo executor de gestos.

## Verificação

A [autoria física no Samsung](benchmarks/script-mode/ui-authoring-arm64-api36.json) verificou criação, rascunho após rotação, confirmação de descarte com cancelamento, salvamento em nova revisão, validação e captura de imagem/região sobre o alvo. A [prévia do recurso](assets/script-mode/recurso-previa-retrato-sm-s916b.png) preservou a seleção e os 1170×101 pixels nas duas orientações. Dois exports por SAF, separados por reimportação, conservaram todos os bytes de código, PNG e manifesto; a revisão inicial também permaneceu intacta.

O [exemplo genérico no Samsung](benchmarks/script-mode/generic-recovery-sm-s916b-api36.json) revelou uma falha durante a animação de saída do diálogo: o script contava frames ainda visíveis como reaparições e esgotava a recuperação. Agora exige uma tela pronta ou concluída, com prazo de dez segundos, antes de reconsiderar outro diálogo. A regressão reproduziu o erro e passou após a correção; outro caso exige parar sem repetir o toque se o diálogo não sair. O alvo de teste ganhou um diálogo opcional, com dois testes nativos aprovados. Após a reconexão, o pacote corrigido [concluiu fisicamente o ciclo](assets/script-mode/recuperacao-corrigida-sm-s916b.png): fechou o diálogo uma vez, reconheceu a tela pronta, executou uma ação e confirmou a conclusão sem intervenção. A suíte atual do runtime também passou nos 69 testes do arm64.

Uma [execução física limitada a uma batalha](benchmarks/script-mode/single-battle-sm-s916b-api36.json), com muros desativados e painel em `(1642,0)`, concluiu abertura, busca, lançamento de tropas, retirada, resultado e retorno confirmado à vila sem intervenção durante o ataque. A sessão [parou no limite declarado](assets/script-mode/batalha-concluida-sm-s916b.png), com zero muros melhorados. Essa posição libera os pontos e as referências da batalha, mas cobre os saldos: não serve como posição validada para o fluxo com muros. O farm completo, o pagamento em elixir, a melhoria de lote e a comparação final de latência permanecem pendentes.

A primeira versão do [controle compacto no Samsung](benchmarks/script-mode/compact-controls-sm-s916b-api36.json) foi conferido após uma batalha atingir a meta e parar porque o painel central cobria a confirmação de retirada. Nessa versão, o painel em execução mostrava a etapa em uma linha; pausa, erro e histórico aberto preservam a apresentação expandida. Na posição `(0,0)`, a janela real mede 698×232 pixels em execução e 698×368 em pausa. O [alvo controlado](assets/script-mode/painel-compacto-sm-s916b.png) comprovou que o ponto `(600,260)` fica livre, enquanto um toque sintetizado no controle solicita pausa; a retomada humana revalida a tela e [alcança o encerramento previsto](assets/script-mode/painel-compacto-retomado-sm-s916b.png).

O [painel estreito no Samsung](benchmarks/script-mode/narrow-controls-sm-s916b-api36.json) reduz a janela em execução para 383×292 pixels. A mensagem expande os controles ao toque; Pausar e Parar permanecem visíveis no estado compacto, e pausa ou erro expandem os controles automaticamente. No alvo controlado, a posição `(0,245)` liberou os pontos `(600,260)`, `(400,400)` e `(250,560)`, preservando também a região textual superior. Um toque sintetizado sobre Parar solicitou pausa; mover e retomar com gesto humano revalidou a tela e concluiu a conferência. A suíte local completa, o build e os 15 testes nativos de recursos passaram. Uma sessão nova revelou ainda uma janela de toque de 562 pixels ao redor do cartão visível de 383 pixels. A regressão reproduziu a mesma diferença; a correção força a nova medição dos botões e ignora posições antigas dos controles centralizados. A repetição física, sem expandir/recolher manualmente, abriu a janela com 383×292 pixels e confirmou os três toques externos, a pausa interna e a retomada. A batalha anterior a essa correção voltou à vila após movimentação manual do painel; não comprova execução sem intervenção. A inspeção posterior de muros em `(0,245)`, com reservas de 1.000.000 nas duas moedas, reconheceu construtor, muro, preços e saldos; selecionou 1→2→1→2 unidades e confirmou 4.000.000 de ouro sem pagar. Os saldos permaneceram iguais. O farm completo nessa posição continua pendente.

A repetição física do pacote limitado a uma batalha, com muros desativados, abriu a busca e reconheceu o oponente, mas parou antes do lançamento: o painel em `(0,0)` cobre a referência textual “Saque”, na região `(160,88)–(430,138)`. Liberar os pontos de gesto não basta; a posição também precisa preservar as regiões de reconhecimento. A posição superior direita `(1642,0)` passou na execução limitada da batalha descrita acima, mas cobre os saldos usados nas melhorias de muros. O percurso completo continua pendente.

As regressões cobrem restauração de posições sobre os eixos, redução da linha de estado, liberação do ponto de lançamento e preservação da posição de leitura no histórico virtualizado. A confirmação atual de retirada também passou no detector nativo sem o painel por cima. A suíte completa e o build FDroid passaram. O percurso de uma batalha sem muros passou na repetição física. Falta validar o fluxo completo com muros e concluir a comparação física de latência.

A [regressão de interseção com os controles no Samsung](benchmarks/script-mode/overlay-intersection-sm-s916b-api36.json) verifica o caso encontrado durante o lançamento de tropas: um gesto sintetizado sobre o painel agora é consumido e solicita pausa cooperativa, com evento `overlay_intersection`. Ele não aciona Parar, Retomar, Diagnóstico ou Mover. A classificação usa o tipo de ferramenta do evento, seguindo `UserTouchWatcher`; o identificador do dispositivo não distingue esses eventos no Android 11. O toque interceptado não é entregue ao aplicativo alvo nem repetido automaticamente.

No alvo controlado arm64/API 36, a sessão permaneceu [pausada com instrução de recuperação](assets/script-mode/controle-intersecao-pausado-sm-s916b.png), permitiu mover o painel com toque humano, revalidou a tela ao retomar e [alcançou o encerramento previsto](assets/script-mode/controle-intersecao-retomado-sm-s916b.png). O botão Parar humano também fechou o overlay. Os testes locais cobrem eventos sintetizados, dedo, caneta e a ligação com pausa/diagnóstico; a suíte completa e o build FDroid passaram. Essa evidência corrige a proteção dos controles e não comprova uma nova execução completa do farm.

A [validação parcial no Samsung SM-S916B arm64, API 36](benchmarks/script-mode/samsung-arm64-partial-validation.json), em 10/11/2026, acrescenta execução física às evidências históricas de emulador abaixo. O runtime passou nos 64 testes na primeira repetição completa e em mais 67 testes, incluindo a recuperação de abertura, antes da última correção do exemplo genérico; a primeira execução teve uma falha de temporização no orçamento de JavaScript ocupado, cuja causa permanece sem confirmação. A coleta inicial arquivada do adaptador registra 13 testes nativos. A suíte atual do adaptador registrou 19 casos: 18 passaram e o teste opcional de dez minutos foi ignorado; inclui retirada, recursos de muros e o diálogo do alvo genérico. As regressões incluem o recorte real de construtores `1/7` (lido pelo OCR como `177`) e a rejeição de um contador sintético `0/7` que o próprio OCR consegue reconhecer.

A vila e os saldos foram reconhecidos no aparelho. As primeiras tentativas de farm pararam por falta de confirmação do construtor. Após a correção, a execução física confirmou o construtor e pesquisou os quatro setores, encerrando sem gastar recursos por não encontrar o muro calibrado. A conta conectada tem muros de nível 16, com custo observado de 2.000.000 por melhoria, enquanto o exemplo seleciona nível 18. O perfil separado `examples/scripts/farm-walls-level16-sm-s916b` foi calibrado com os recortes reais: seleção, preço e confirmação em ouro passaram no detector nativo. Uma variante limitada melhorou um muro de nível 16 para 17 por 2.000.000 de ouro; a confirmação por OCR foi corrigida e conferida posteriormente. A [confirmação em elixir](benchmarks/script-mode/elixir-confirmation-sm-s916b-api36.json) foi reconhecida fisicamente sem pagar. A [inspeção de lote](benchmarks/script-mode/batch-inspection-sm-s916b-api36.json) confirmou a sequência de seleção 1→2→1→2 e a confirmação de 4.000.000 de ouro, sem executar o pagamento. Os recortes e as posições do perfil nível 16 foram corrigidos; os saldos permaneceram iguais durante as inspeções. Pagamento em elixir e melhoria de lote permanecem pendentes. O farm completo e a comparação física de latência continuam pendentes. O controle flutuante também teve sua posição reajustada após mudanças no tamanho da janela, com testes locais e conferência visual no Samsung.

Com JDK 21 e o wrapper:

```sh
./gradlew testFDroidDebugUnitTest assembleFDroidDebug
ANDROID_SERIAL=emulator-5582 ./gradlew :core:script:runtime:connectedFDroidDebugAndroidTest
ANDROID_SERIAL=emulator-5582 ./gradlew :core:script:android:connectedFDroidDebugAndroidTest
```

Substitua o serial pelo dispositivo de teste. A suíte do runtime também compila para Android. Os testes dos exemplos carregam os mesmos arquivos JavaScript distribuídos no pacote, com telas determinísticas e executor de ações falso. A suíte nativa do adaptador usa JNI, OCR, templates e recortes reais, sem despachar ações no jogo.

As coletas históricas abaixo exercitaram runtime e adaptador em emuladores x86_64 API 24 e 36. A execução arm64 parcial está descrita acima; o percurso completo do farm no aparelho continua pendente. Instrumentação/UiAutomation pode interromper a acessibilidade; nunca execute seus testes ao mesmo tempo que uma automação no mesmo aparelho.

Após a otimização de HSV e da confirmação, passaram em ambas as versões do Android: **58 testes do runtime**, **27 de detecção de cores/muros/tropas** e **5 do adaptador com o detector real**. O build FDroid e a suíte local completa também passaram. A instalação na API 24 usou `--no-streaming`, seguida da execução direta pelo runner.

Na API 24, o envio do APK nativo grande falhou em `install-write` pelo Gradle, antes de iniciar testes. A instalação sem streaming permitiu executar os mesmos cinco testes:

```sh
adb -s emulator-5584 install --no-streaming -r core/script/android/build/outputs/apk/androidTest/fDroid/debug/android-fDroid-debug-androidTest.apk
adb -s emulator-5584 shell am instrument -w -r -e class com.buzbuz.smartautoclicker.core.script.android.NativeScriptResourcesIntegrationTest com.buzbuz.smartautoclicker.core.script.android.test/androidx.test.runner.AndroidJUnitRunner
```

### Cobertura do aceite

A implementação e os resultados abaixo ainda não encerram o aceite no aparelho físico.

| Área | Evidência disponível | Pendente |
| --- | --- | --- |
| Base anterior | Backup original preservado e referência do motor de eventos com o farm equivalente. | — |
| Runtime | JavaScript real, módulos, espera, limites, erro localizado, interrupção e limpeza em JVM, x86_64 API 24/36 e arm64 API 36; ABIs distribuídas compiladas. | Esclarecer a falha intermitente inicial no teste de orçamento ocupado, apesar da repetição completa sem falhas. |
| Sessão e API | Testes de propriedade exclusiva, pausa/revalidação, frame antigo, cancelamento e liberação; captura e gestos reais no alvo controlado, incluindo pausa por interseção e retomada no arm64. | Concluir a conferência física de ciclo de vida e regressões dos modos existentes. |
| Pacotes | Validação, importação/exportação, revisões imutáveis, parâmetros e calibração; exportação e reimportação via seletor Android. | — |
| Autoria e acompanhamento | Editor, rascunho, formulário, captura, prévia, rotação e transporte SAF conferidos no Samsung; diagnóstico exercitado no alvo controlado e no jogo, além dos testes de emulador. | Conferir os demais cenários de ciclo de vida e regressões dos modos anteriores no aparelho. |
| Exemplos | Farm/muros e exemplo genérico com JavaScript real e telas determinísticas; reconhecimento nativo dos recortes. | Ciclo físico limitado a duas batalhas e três melhorias concluído; conferir sessões prolongadas e demais configurações. |
| Latência | Metas agregadas da confirmação e do painel atendidas em ensaios dedicados, mantendo as conferências; custos e variação registrados. | Comparação do percurso completo no aparelho físico com reconhecimento equivalente. |
| Documentação | API, pacotes, exemplos, limites, extensões e protocolo de medição descritos. | Incorporar o resultado físico antes de declarar o aceite final. |


A [repetição com o painel estreito corrigido](benchmarks/script-mode/single-battle-narrow-controls-sm-s916b-api36.json), em `(0,245)`, concluiu busca, lançamento, retirada, resultado e retorno confirmado à vila sem intervenção durante a batalha. A sessão parou no limite de uma batalha, com melhorias desativadas. Essa execução valida a posição no ataque; o percurso completo com muros e o ganho físico de latência continuam pendentes.

### Ciclo físico com melhorias no Samsung

A [validação do ciclo com muros](benchmarks/script-mode/farm-wall-cycle-sm-s916b-api36.json) concluiu duas batalhas e confirmou três melhorias na mesma sessão, sem intervenção durante a execução, com painel em `(0,245)`. Usou melhoria por saldo disponível, lotes de até dois, reservas de 13 milhões de ouro e 19 milhões de elixir e limite de duas batalhas. Após voltar à vila, o script reavaliou as melhorias, preservou as reservas e encerrou no limite configurado. Isso valida o ciclo limitado; não comprova execução indefinida nem ganho de latência.

As tentativas anteriores revelaram dois problemas de calibração: o título “Muro” se desloca na seleção de duas unidades e a região numérica da confirmação de lote incluía a primeira letra de “Elixir”, lida como `1`. Os recortes reais agora têm regressões nativas, preservando título, moeda e total exato. Dois muros foram pagos em ouro antes da parada no título; os pagamentos físicos em elixir foram de um muro por vez. O lote de dois em elixir passou no detector nativo, sem pagamento físico nessa forma.

Quando os quatro setores não encontram muro elegível, a rotina agora devolve o controle ao farm sem pagar. A execução física confirmou essa recuperação e a entrada no ataque seguinte. A suíte local completa e o build FDroid passaram; os 17 testes nativos de recursos passaram após os ajustes de regiões. Permanecem abertas as verificações gerais de ciclo de vida, a falha intermitente do watchdog e a comparação física de latência.

## Medições de latência

A [coleta arm64 com confirmação equivalente](benchmarks/script-mode/farm-confirmation-arm64-api36.json) executou seis rodadas por caminho, alternando a ordem, com três aquecimentos e vinte amostras por rodada. Cada caminho reteve 120 amostras, sem falhas de reconhecimento. O bitmap estava disponível e os gestos eram falsos; captura pelo sistema, resposta do jogo e painel visível ficam fora desse ensaio.

| Caminho no Samsung, detector atualizado | p50 / p95 | Amplitude entre rodadas, p50 / p95 |
| --- | --- | --- |
| Farm por eventos | 139,166 / 143,132 ms | 70,791 / 60,857 ms |
| JavaScript na thread dos recursos | 142,088 / 146,099 ms | 5,216 / 4,697 ms |
| JavaScript na thread dos recursos, diagnóstico detalhado | 142,034 / 145,503 ms | 5,344 / 4,647 ms |

O JavaScript não superou o motor de eventos atualizado além da variação observada. A coleta não compara o detector anterior e não demonstra o aceite final de latência. As metas históricas abaixo foram definidas no emulador; não são limites automaticamente transferíveis ao Samsung.

A referência inicial do detector está em [native-detector-x86_64-api36.json](benchmarks/script-mode/native-detector-x86_64-api36.json). Ela mede somente vinculação do bitmap, OCR numérico e cor. Não comprova ganho de latência do novo motor. A comparação final deve usar os mesmos frames, resolução, regiões, critérios, rodadas e taxas de reconhecimento, separando captura, ponte, espera e início do gesto das animações do aplicativo alvo.

A [comparação inicial dos caminhos de reação](benchmarks/script-mode/reaction-baseline-x86_64-api36.json) usa `ScenarioProcessor` e `ScriptSession` reais com o mesmo detector nativo, bitmap de 930 × 945, região, limiar e formato numérico. São cinco rodadas alternando a ordem dos motores, três aquecimentos e vinte amostras por caminho em cada rodada. Todas as amostras conferem o valor `4200000`, a cor disponível e a entrega de um único gesto ao executor falso.

| Caminho | Reação p50 / p95 | Coordenação e ponte p50 | Amplitude das medianas entre rodadas |
| --- | --- | --- | --- |
| Cenário atual | 28,100 / 29,743 ms | 0,759 ms | 0,997 ms |
| JavaScript inicial | 30,981 / 33,407 ms | 3,861 ms | 1,915 ms |

Neste recorte, o JavaScript acrescenta custo. O resultado ainda não demonstra uma otimização. A medição começa com um bitmap disponível e termina em `AndroidActionExecutor.dispatchBuiltGesture`; não inclui captura pelo sistema, injeção do gesto, espera de observação nem resposta do jogo. O campo `captures` das amostras conta vínculos desse bitmap ao detector. CPU do processo e snapshots de heap incluem aquecimento e preparação; não medem bateria nem alocações por operação. O histórico visível e o registro de eventos de diagnóstico ainda precisam de comparação própria.

Antes de alterar o caminho, ficam fixadas as metas intermediárias deste benchmark: coordenação/ponte p50 de até **1,8 ms**, reação p50 de até **29 ms** e redução da reação superior aos **1,915 ms** de variação inicial. Devem permanecer os mesmos pixels, critérios e conferências, sem falhas de reconhecimento. Atingir essas metas reduz o custo inicial do JavaScript; o aceite de desempenho continua exigindo ganho contra o motor atual além da variação de ambos e medição do fluxo completo.

A [coleta com `readAll`](benchmarks/script-mode/grouped-read-x86_64-api36.json) compara três caminhos na mesma execução, alternando qual deles começa cada rodada. Para duas consultas, a coordenação/ponte p50 ficou em 3,071 ms nas chamadas individuais e 2,762 ms no lote. A reação p50 ficou em 30,288 ms e 30,082 ms, respectivamente, contra 28,135 ms do cenário atual. Todas as cem amostras de cada caminho mantiveram o reconhecimento esperado. A diferença de reação ficou abaixo da variação entre rodadas e nenhuma meta intermediária foi atingida. Esse resultado não comprova ganho de latência; a comparação do fluxo completo e o custo do diagnóstico continuam pendentes.

Para reproduzir a comparação, sem automação ativa no dispositivo:

```sh
ANDROID_SERIAL=emulator-5582 ./gradlew :core:smart:processing:connectedFDroidDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.buzbuz.smartautoclicker.core.processing.data.processor.ScriptReactionLatencyBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.benchmark=true
```

O teste é opcional e não impõe limites de tempo à suíte comum. A versão atual mede cenário, JavaScript com leituras individuais e JavaScript com lote. As amostras ficam em `benchmarkJson` nas métricas do resultado instrumentado. O JSON versionado preserva dados brutos, comando, hashes dos fontes/fixture e limitações da coleta.

As coletas iniciais de reação e os três relatórios de confirmação abaixo usam runtime e detector na mesma thread dedicada. Esse recorte isola os caminhos e as alterações de reconhecimento. A configuração inicial do serviço usava QuickJS em `Dispatchers.Default` e operações Android em outra thread; a seção de diagnóstico registra a comparação e a escolha posterior da thread compartilhada. Os resultados anteriores não incluíam o custo da disposição inicial do serviço.

### Confirmação do farm

A [referência da confirmação de lote](benchmarks/script-mode/farm-confirmation-baseline-x86_64-api36.json) carrega o backup anterior completo, com 323 eventos, pelo desserializador e pelo mapeador de domínio de produção. Cada amostra começa com `muros_etapa=24`, `muros_lote_total=8400000` e a aplicação da etapa pendente. A criação desse estado fica fora da medição; a seleção por gatilhos, os eventos, a detecção e a preparação do gesto ficam dentro. O caminho JavaScript chama a função real `confirmPayment` do exemplo.

Os dois caminhos recebem o mesmo frame de 2340 × 1080, montado com o recorte de confirmação. Cada amostra confere os mesmos conjuntos de consultas nativas — regiões, limiares, formato numérico e textos — e os templates precisam ter pixels idênticos. As regiões das imagens do exemplo incluem a mesma margem de um pixel aplicada pelo modo de eventos à detecção exata. O JavaScript inicial faz uma leitura adicional do título, contabilizada no resultado.

| Caminho de confirmação | p50 / p95 | Amplitude das medianas entre rodadas |
| --- | --- | --- |
| Farm anterior | 78,720 / 85,841 ms | 2,928 ms |
| JavaScript inicial | 107,315 / 115,052 ms | 6,187 ms |

As metas para a próxima alteração ficam fixadas em **p50 até 71 ms** e **p95 até 77 ms**. Cada limite parte do percentil do farm anterior, subtrai a maior amplitude entre rodadas de qualquer caminho e mais 1 ms, arredondando para baixo. O reconhecimento deve continuar sem falhas e com os mesmos critérios. A referência mostra uma captura extra do título e o custo elevado da primeira detecção de imagem; não demonstra ganho do JavaScript.

A primeira mudança converte para HSV apenas a região candidata do template. Se os recortes acumulados fossem atingir a área da tela, o detector passa a usar o cache completo pelo restante daquele frame. Os passos de conversão de cor permanecem os mesmos e o orçamento reinicia com outra imagem. Os 27 testes nativos de cor, muros e tropas passaram, incluindo correspondências repetidas, troca de cores entre frames e busca na tela inteira. A [medição intermediária](benchmarks/script-mode/farm-confirmation-roi-x86_64-api36.json) reduziu a reação JavaScript para 73,382/80,667 ms em p50/p95; ainda não atingia as metas.

A segunda mudança retirou a observação inicial duplicada do título. A rotina declara a conferência de retomada antes do lote e verifica título, botão, moeda e valor na mesma observação. O teste de pausa durante esse reconhecimento exige revalidar a confirmação e observar novamente antes de pagar. A [coleta após as duas mudanças](benchmarks/script-mode/farm-confirmation-optimized-x86_64-api36.json) registrou:

| Caminho após as mudanças | p50 / p95 |
| --- | --- |
| Farm por eventos, com detector atualizado | 59,692 / 62,674 ms |
| JavaScript, com detector atualizado e uma observação | 64,275 / 68,613 ms |

Neste recorte, o JavaScript atingiu os limites de 71/77 ms e ficou 14,445/17,228 ms abaixo da referência anterior por eventos. Esses ganhos superam as amplitudes iniciais de ambos os caminhos, com zero falhas de reconhecimento e os mesmos critérios nativos. A melhora vem principalmente do detector compartilhado: o modo de eventos atualizado também ficou mais rápido e continua à frente do JavaScript nesta comparação. O resultado não comprova latência do farm completo nem inclui captura pelo sistema, resposta do jogo ou custo do diagnóstico visível.

```sh
ANDROID_SERIAL=emulator-5582 ./gradlew :core:smart:processing:connectedFDroidDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.buzbuz.smartautoclicker.core.processing.data.processor.FarmReactionLatencyBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.benchmark=true
```

Este benchmark requer API 31 ou superior para obter as métricas de um display virtual privado, sem mudar a configuração do emulador. O backup de referência fica apenas nos assets de teste e é carregado em memória; o banco do usuário não é acessado. A medição cobre a confirmação já aberta, sem captura pelo sistema, injeção de gestos, espera de observação ou resposta do jogo. O percurso completo e os custos de CPU, memória e diagnóstico ainda precisam de avaliação própria.

Os três relatórios dessa fase incluem `sourceSnapshots` dos fontes listados, conferidos contra seus hashes. Assim, os trechos anteriores à otimização permanecem disponíveis junto dos dados brutos. Reproduza uma revisão histórica em uma cópia descartável do checkout, conferindo também os demais hashes e o ambiente registrado; não substitua arquivos de trabalho para executar uma comparação.

### Diagnóstico e configuração de threads da produção

O painel detalhado habilita tempos, resultados e metadados limitados das operações. Fechar ou ocultar o painel desativa a coleta detalhada; a etapa/mensagem principal usa o último evento do script. A interface atualiza o histórico aberto até cinco vezes por segundo e usa RecyclerView para formatar apenas as linhas visíveis. Um teste de integração também cobre a expansão da janela: o texto precisa ficar visível dentro do overlay ao abrir o painel.

Editor e painel usam a localização fornecida por `ScriptExecutionException`. O painel apresenta também `scriptStack` e a cadeia de causas Kotlin. O teste de sintaxe do editor executa o QuickJS real com JNI de JVM; a substituição do artefato fica restrita às configurações de teste local, assim como nos módulos do núcleo. Os APKs usam o artefato Android.

A [primeira coleta com os dispatchers da produção](benchmarks/script-mode/farm-diagnostics-baseline-x86_64-api36.json) usa QuickJS em `Dispatchers.Default` e Android na thread serial de recursos. Compara a mesma confirmação em cinco rodadas, alternando a ordem de três caminhos. A coleta inclui o registro de diagnóstico e mantém seu painel desconectado; o custo de renderização da interface ainda não foi medido.

| Caminho | p50 / p95 | Mediana de CPU do processo por rodada |
| --- | --- | --- |
| Farm por eventos atualizado | 62,630 / 75,080 ms | 1520 ms |
| JavaScript, detalhamento desligado | 70,420 / 90,955 ms | 1851 ms |
| JavaScript, detalhamento ligado | 71,579 / 90,615 ms | 1910 ms |

Cada rodada de CPU inclui vinte amostras, três aquecimentos, preparação do runtime/estado e instrumentação do teste. Os snapshots de heap/PSS incluem modelos, fixtures, dados de medição e variação do coletor de lixo. O PSS observado nos caminhos JavaScript ficou entre 122453 e 127136 KiB; esses números não são alocações por operação nem medições do processo do aplicativo em aparelho físico.

O histórico normal reteve 23 eventos de etapa; o detalhado atingiu o limite de 200, dos quais 180 eram operações. A diferença de latência entre os modos ficou abaixo da variação entre rodadas. A configuração da produção atingiu a meta intermediária de p50, mas ultrapassou a de p95; ainda não comprova o aceite de latência. As primeiras rodadas variaram bastante e o ensaio preserva o protocolo inicial de três aquecimentos. A coleta dessa referência usa três caminhos; a reprodução atual inclui os controles descritos abaixo.

Passaram os testes locais do painel e de seus metadados, a suíte completa e o build FDroid. O runtime com os testes de diagnóstico e geometria passou em **62 testes na API 36**, além dos cinco testes nativos do adaptador. Os mesmos **62 testes do runtime passaram na API 24**, incluindo diagnóstico e revalidação da geometria.

### Reutilização da geometria do perfil

A [coleta do transporte do perfil](benchmarks/script-mode/farm-profile-baseline-x86_64-api36.json) registrou 100 consultas ao perfil no caminho JavaScript normal. Cada resposta tinha 5.784 caracteres; o tempo p50 dentro do host foi 1,817 ms. Esse tempo inclui serialização e exclui a interpretação da resposta no JavaScript e a travessia entre dispatchers.

O exemplo agora conserva a geometria do perfil durante uma execução do pacote imutável. Uma mudança nas dimensões da observação exige nova validação do perfil. Cada observação ainda captura e reconhece a tela novamente; as posições recebem o identificador do frame atual. O teste de integração cobre posições antigas rejeitadas, mudança de conteúdo com as mesmas dimensões, escala compatível e dimensões incompatíveis.

A [comparação com o controle sem reutilização](benchmarks/script-mode/farm-geometry-cache-x86_64-api36.json) executa quatro caminhos nas mesmas cinco rodadas, com cem amostras por caminho e zero falhas de reconhecimento:

| Caminho | p50 / p95 | Consultas ao perfil no trecho medido |
| --- | --- | --- |
| Farm por eventos atualizado | 62,777 / 70,630 ms | 0 |
| JavaScript, geometria reutilizada | 67,388 / 86,816 ms | 0 |
| JavaScript, geometria reutilizada e diagnóstico detalhado | 66,931 / 86,766 ms | 0 |
| JavaScript, controle sem reutilização | 71,162 / 92,001 ms | 100 |

A geometria é inicializada no aquecimento, que fica fora da reação medida. Os quatro caminhos alternam a posição inicial; com cinco rodadas, eventos começa duas vezes e cada outro caminho uma vez. A diferença entre JavaScript com e sem reutilização ficou abaixo da maior amplitude de medianas entre rodadas (8,491 ms). O resultado registra a eliminação do transporte repetido, mas ainda não demonstra ganho além da variação nem atende à meta de p95 até 77 ms. Captura pelo sistema, gestos reais e painel visível permanecem fora deste ensaio.

### Thread compartilhada no serviço

A [primeira comparação das disposições de threads](benchmarks/script-mode/farm-dispatcher-comparison-x86_64-api36.json) manteve cinco caminhos em cinco rodadas. O caminho compartilhado atingiu 62,988/70,840 ms em p50/p95. A [coleta com diagnóstico nas duas disposições](benchmarks/script-mode/farm-shared-thread-diagnostics-x86_64-api36.json) acrescentou o detalhamento na thread compartilhada e usou seis rodadas para que cada um dos seis caminhos começasse uma vez. Permanecem três aquecimentos e vinte amostras por rodada; são 120 amostras por caminho, sem falhas de reconhecimento.

| Caminho | p50 / p95 | Mediana de CPU do processo por rodada |
| --- | --- | --- |
| Farm por eventos atualizado | 60,673 / 64,665 ms | 1463 ms |
| JavaScript em Default, geometria reutilizada | 65,304 / 84,860 ms | 1703,5 ms |
| JavaScript em Default, diagnóstico detalhado | 64,923 / 85,840 ms | 1687 ms |
| JavaScript em Default, sem reutilização | 67,900 / 87,802 ms | 1806,5 ms |
| JavaScript na thread dos recursos | 62,003 / 65,296 ms | 1548,5 ms |
| JavaScript na thread dos recursos, diagnóstico detalhado | 61,770 / 65,606 ms | 1535 ms |

Os dois caminhos compartilhados atendem aos limites agregados de 71/77 ms fixados na referência da confirmação. Em relação ao farm anterior às otimizações, o caminho compartilhado sem detalhamento reduziu p50/p95 em cerca de 16,7/20,5 ms, acima das amplitudes iniciais de 6,2/7,5 ms. O detector atualizado também beneficia eventos, que continua à frente no p50. Não atribua todo o ganho à disposição das threads: a diferença de p50 para Default ficou abaixo da variação desse controle, e uma rodada compartilhada teve um pico de p95 fora do padrão agregado.

`PackageScriptExecution` agora escolhe a thread dos recursos como padrão do interpretador. Os 64 testes do runtime e os cinco testes nativos do adaptador passaram nas APIs 24 e 36, além da suíte local completa e do build FDroid. Os testes com QuickJS real conferem que uma chamada bloqueante do host pode ultrapassar o orçamento de JavaScript ocupado, enquanto loops ainda são interrompidos por limite e por cancelamento; uma sessão seguinte consegue adquirir os recursos. O watchdog permanece independente dessa thread.

O relatório maior ultrapassou o limite de uma transação Binder. O benchmark agora envia o JSON depois das medições em chaves `benchmarkJsonPart0000`, `benchmarkJsonPart0001` etc., com até 60.000 caracteres por parte. Para recuperar o relatório, leia os pares `key`/`value` em `test-result.textproto`, confira que cópias da mesma chave coincidem, ordene as chaves e concatene os valores antes de interpretar o JSON. O runner remove o APK e seu cache ao finalizar; não dependa de arquivos privados deixados pelo teste.

As medições continuam restritas ao bitmap disponível e à entrega ao executor falso. Captura pelo sistema, espera ativa, início do gesto real, renderização do painel e percurso completo do farm precisam de avaliação própria. O painel foi conferido manualmente na API 36: [histórico aberto](assets/script-mode/diagnostico-aberto-api36.png) e [execução pausada](assets/script-mode/diagnostico-pausado-api36.png). O APK atualizado também executou quatro observações e exibiu o [erro intencional em `entry.js:8:16`](assets/script-mode/erro-localizado-api36.png), liberando a captura ao falhar. A [evidência da conferência](benchmarks/script-mode/ui-diagnostics-x86_64-api36.json) preserva a fixture, hashes e o APK usado em cada captura. São verificações visuais, sem medição de desempenho.

### Contrato nativo e sessões longas

O adaptador Android recebe o mesmo `AppComponentsProvider.originalAppId` usado pelo modo de eventos e o encaminha como metadado de `ImageDetector.setScreenBitmap`. Esse campo não é um rótulo de observação. O decoder nativo existente usa o identificador para aceitar novas imagens durante a vida do processo; o rótulo anterior do script fazia as novas sessões falharem após dez minutos. A correção preserva as verificações nativas e sua configuração.

Os testes locais conferem o encaminhamento pelo serviço, execução do pacote e adaptador. O teste nativo opcional `NativeScreenLifetimeIntegrationTest.aFreshNativeSessionAcceptsItsScreenAfterTenMinutesInTheSameProcess` cria uma sessão antes e outra depois de 610 segundos, sem substituir o relógio ou alterar o decoder. Ele é ativado pelo argumento de instrumentação `longevity=true`; não faz captura pelo sistema nem despacha gestos. A [execução na API 36 x86_64](benchmarks/script-mode/native-screen-lifetime-x86_64-api36.json) passou em dois testes, após 610,049 segundos. A cor esperada foi aceita e a oposta foi rejeitada com tolerância zero antes e depois da espera. A suíte nativa curta também passou nas APIs 24 e 36: sete casos executados e o caso longo opcional omitido nessa execução comum.

A [pré-coleta da captura](benchmarks/script-mode/capture-preflight-failures-x86_64-api36.json) foi invalidada: o roteiro também usava incorretamente tolerância de cor 100. Ela preserva os fontes, dados brutos e a parada sem toque que revelou o problema de duração. Seus números não servem de aceite de reconhecimento ou latência. O roteiro corrigido usa tolerância zero e um teste nativo confere que a cor oposta é rejeitada.

### Rascunhos grandes na rotação

O texto do editor é salvo uma vez, pelo `SavedStateHandle`. Cursor e rolagem usam um estado pequeno separado; o EditText não duplica o texto no estado da view. Antes da correção, o teste com um rascunho de 256 KiB gerava 1.054.720 bytes de estado salvo. A regressão exige menos de 750 KiB de estado e confere texto e seleção após recriar a Activity, deixando margem para os dados do framework no transporte por Binder.

### Captura e gestos no alvo controlado

A [coleta física no Samsung](benchmarks/script-mode/capture-gesture-arm64-api36.json) completou cinco rodadas por modo, alternando a ordem, com três aquecimentos e vinte amostras por execução: **200 amostras e 230 toques confirmados**, sem falhas de reconhecimento nem toques ausentes, duplicados ou cancelados no conjunto medido. Uma preparação sem o modo detalhado pretendido foi excluída antes de inspecionar os tempos; ela confirmou outros 23 toques no alvo e está registrada separadamente.

| Intervalo no Samsung | Histórico fechado, p50 / p95 | Histórico aberto, p50 / p95 |
| --- | --- | --- |
| Espera pela próxima cor | 43,450 / 49,264 ms | 36,280 / 50,234 ms |
| Observação e marcas de relógio | 0,637 / 0,810 ms | 0,817 / 11,182 ms |
| Leituras agrupadas e marcas de relógio | 0,797 / 0,970 ms | 0,957 / 1,286 ms |
| Reconhecimento até DOWN no alvo | 2,415 / 3,000 ms | 2,441 / 2,768 ms |
| Início da observação até DOWN no alvo | 3,832 / 4,620 ms | 4,265 / 14,158 ms |
| Reconhecimento até retorno de tap e relógio | 3,315 / 4,114 ms | 3,356 / 4,364 ms |
| UP anterior até o próximo DOWN | 47,270 / 53,887 ms | 42,750 / 58,346 ms |

O [alvo confirmou os toques](assets/script-mode/latencia-alvo-sm-s916b.png) e o diagnóstico aberto reteve 200 eventos por rodada. A cauda da observação aumentou com o painel aberto; essa coleta não incluiu CPU/PSS nem comparação com eventos no mesmo alvo. Cores planas e pressão de 1 ms não representam OCR/imagens do farm, e o tempo de observação não mede a produção do frame pelo compositor. O resultado comprova o percurso físico medido, sem demonstrar ganho final de latência.

O APK de instrumentação de `core/script/android` contém `LatencyTargetActivity`, uma tela isolada que alterna entre verde e azul após cada toque concluído no retângulo inferior. Ela registra `down`, `up`, cancelamento e desenho com `System.nanoTime()`. O evento de desenho não é uma confirmação de apresentação pelo compositor. Essa atividade não faz parte dos APKs do aplicativo.

O pacote de teste fica em `core/script/android/src/androidTest/assets/latency-target/`. Ele exige a cor de identificação e a cor esperada do retângulo antes de autorizar cada toque, confirma a transição seguinte e para sem tocar se o alvo não corresponder. São três aquecimentos e vinte amostras; `now()` marca espera, observação, reconhecimento e retorno do gesto. O relatório inteiro é registrado no último evento do script, evitando perder amostras ao atingir o limite do histórico detalhado. Os testes executam o JavaScript real com e sem detalhamento e conferem também a rejeição de outro alvo.

Para recuperar o histórico retido da sessão sem alterar a coleta, mantenha seu overlay aberto e execute:

```sh
adb -s emulator-5582 shell dumpsys activity service com.buzbuz.smartautoclicker.debug/com.buzbuz.smartautoclicker.SmartAutoClickerService --script-json
```

Cada evento usa o JSON já definido por `ScriptDiagnostic`; o wrapper do Android pode acrescentar cabeçalhos ao comando. A opção não liga o detalhamento nem grava logs em disco. Depois de fechar a sessão, seu histórico deixa de estar disponível. Um teste de integração cobre retenção, exportação e encerramento desse acesso.

### Medição com captura, gesto real e painel visível

A [coleta no alvo controlado](benchmarks/script-mode/capture-gesture-diagnostics-x86_64-api36.json) usa o APK corrigido e o pacote de tolerância zero. Foram cinco rodadas por modo, três aquecimentos e vinte amostras por rodada: **200 amostras válidas e 230 toques confirmados**. A ordem inicial alterna entre os modos; o painel fechado começa três pares e o aberto começa dois. Cada execução reinicia o processo do alvo e conserva o processo do Klick’r. Não houve falha nas conferências medidas nem toque ausente, duplicado ou cancelado. Ausências temporárias durante a espera pela próxima cor são esperadas.

| Intervalo | Painel fechado, p50 / p95 | Painel aberto, p50 / p95 |
| --- | --- | --- |
| Espera pela condição, com observações e polling | 47,885 / 56,391 ms | 48,259 / 63,429 ms |
| Chamada de observação e marcas de relógio | 0,554 / 1,133 ms | 0,691 / 1,244 ms |
| Leituras agrupadas e marcas de relógio | 0,724 / 1,635 ms | 0,865 / 1,624 ms |
| Reconhecimento até DOWN recebido pelo alvo | 1,369 / 1,937 ms | 1,407 / 1,907 ms |
| Início da observação até DOWN no alvo | 2,621 / 4,588 ms | 3,007 / 5,246 ms |
| Reconhecimento até retorno de tap e relógio seguinte | 2,827 / 4,226 ms | 3,024 / 32,674 ms |
| UP anterior no alvo até o próximo DOWN | 51,795 / 60,735 ms | 53,401 / 69,425 ms |

A observação pode reutilizar a última imagem de MediaProjection, pois a espera já procurou a próxima cor. Seu tempo não é a latência de produção de um frame pelo compositor. O intervalo entre toques inclui desenho do alvo, disponibilidade da imagem e polling, além do motor. Os horários de DOWN/UP são medidos no callback do aplicativo alvo. O retorno da chamada de gesto não garante que o alvo já tenha processado UP; o roteiro confirma a nova cor antes de continuar.

O histórico aberto reteve 200 eventos, dos quais 198 eram operações; o relatório agregado do script preservou todas as vinte amostras de cada rodada. Os eventos detalhados ainda disponíveis permitem parear 32 observações e 33 lotes: os tempos p50 dentro do host foram 0,098 ms e 0,185 ms, respectivamente. Os resíduos correspondentes, 0,596 ms e 0,642 ms, incluem ponte, serialização, relógio e registro de diagnóstico. Não representam exclusivamente comunicação JavaScript/Kotlin.

A mediana de CPU do processo no intervalo completo foi **1470 ms** com painel fechado e **2100 ms** com painel aberto. Esse intervalo inclui a consulta final de consentimento, preparação, espera inicial de vinte segundos, aquecimentos, coleta e limpeza; não isola o custo de renderização. Os snapshots de PSS após a limpeza variaram de 129877 a 147267 KiB e incluem o aquecimento do mesmo processo, sem medir pico ou alocações por operação.

A diferença até o início do toque ficou abaixo da variação entre rodadas. Já a cauda do retorno do gesto e o custo de CPU aumentaram com o painel aberto. Nos 33 eventos `tap` detalhados retidos, o p95 dentro do host foi 26,800 ms. Nessa versão, o histórico formatava e media um TextView com a lista inteira na thread principal, onde o Android também entrega os callbacks de gesto. Essa referência motivou a avaliação da lista virtualizada abaixo. Este ensaio usa cores planas e pressão de 1 ms; não substitui OCR/imagens do farm, um comparativo de eventos neste alvo ou a validação arm64.

### Histórico com lista virtualizada

O histórico passou a usar RecyclerView, mantendo os 200 eventos acessíveis e formatando as linhas quando ficam visíveis. A lista acompanha novos eventos enquanto está no fim e preserva a leitura de eventos anteriores. Erros abrem com arquivo, linha e pilha no início do painel. Os testes cobrem retenção, rolagem, atualização durante a leitura e desativação do detalhamento ao fechar.

A [nova coleta](benchmarks/script-mode/capture-gesture-recycler-x86_64-api36.json) repetiu as dez execuções, ordem, aquecimentos, tolerância zero e espera inicial da referência anterior. Foram **200 amostras válidas e 230 toques confirmados**, sem falhas nas conferências medidas. Os [limites de comparação](benchmarks/script-mode/diagnostic-renderer-targets-x86_64-api36.json) foram registrados depois da implementação e antes de inspecionar os novos resultados: p95 agregado e de cada rodada detalhada até 17 ms, e p95 agregado com painel fechado até 6 ms. São critérios desta comparação, sem pré-registro do experimento.

| Intervalo | Painel fechado, p50 / p95 | Painel aberto, p50 / p95 |
| --- | --- | --- |
| Espera pela condição, com observações e polling | 48,457 / 61,452 ms | 49,700 / 60,770 ms |
| Chamada de observação e marcas de relógio | 0,573 / 1,258 ms | 0,702 / 1,349 ms |
| Leituras agrupadas e marcas de relógio | 0,736 / 1,758 ms | 0,906 / 1,379 ms |
| Reconhecimento até DOWN recebido pelo alvo | 1,357 / 2,001 ms | 1,396 / 1,873 ms |
| Início da observação até DOWN no alvo | 2,713 / 4,713 ms | 3,082 / 4,724 ms |
| Reconhecimento até retorno de tap e relógio seguinte | 2,925 / 4,115 ms | 3,087 / 13,055 ms |
| UP anterior no alvo até o próximo DOWN | 52,526 / 66,748 ms | 54,520 / 66,698 ms |

O p95 do intervalo até o retorno do gesto com painel aberto caiu de 32,674 para 13,055 ms. Os p95 das cinco rodadas ficaram entre 3,771 e 14,733 ms; todos passaram no limite de 17 ms e ficaram abaixo da melhor rodada da referência, de 18,966 ms. Nos 32 eventos `tap` detalhados retidos, o p95 dentro do host foi 11,809 ms. O controle com painel fechado também passou no limite de 6 ms. O intervalo entre toques não apresentou ganho consistente acima da variação observada.

A mediana de CPU no intervalo completo foi 1460 ms com painel fechado e 1870 ms com painel aberto, ante 1470 e 2100 ms na referência. Esses valores incluem consentimento, preparação, espera inicial, aquecimentos e limpeza; não isolam a renderização. A lista nova acompanha os eventos recentes, enquanto o TextView anterior permanecia no início: a comparação abrange o comportamento completo de cada visualizador. Os limites de captura, relógio, retenção e alvo controlado descritos acima continuam válidos.

A [conferência visual da lista](benchmarks/script-mode/ui-diagnostic-recycler-x86_64-api36.json) usa um overlay criado com fonte em 1,3 e modo escuro. O erro `entry.js:8:16`, a pilha e os controles ficaram visíveis em [retrato](assets/script-mode/erro-lista-fonte-ampliada-api36.png) e [paisagem](assets/script-mode/erro-lista-fonte-ampliada-paisagem-api36.png); a captura estava liberada após a falha. São verificações manuais na API 36 x86_64. O farm completo e a execução arm64 permanecem pendentes.

### Tamanho dos APKs

A [coleta do build com a lista virtualizada de diagnóstico](benchmarks/script-mode/apk-size-diagnostic-recycler-fdroid-debug.json) registra tamanho, SHA-256 e entradas nativas do QuickJS nos cinco APKs FDroid debug. O APK arm64 tem **36482863 bytes**, o x86_64 tem **40942689 bytes** e o universal tem **74830012 bytes**. As entradas nativas do QuickJS ocupam, respectivamente, 792672, 835176 e 2978224 bytes dentro dos ZIPs.

São totais desse build. A contribuição de Dex/recursos e o incremento em relação a um APK anterior permanecem sem isolamento. Os pacotes das quatro ABIs foram compilados; a execução verificada continua restrita a x86_64. Builds de release, inicialização e consumo de bateria precisam de medições próprias.

### Prévia dos recursos do pacote

Selecionar um PNG nos detalhes mostra a imagem e suas dimensões originais. A decodificação ocorre em I/O e limita o maior lado da prévia a 1024 pixels, sem modificar o arquivo usado pelo detector. A rotação conserva a seleção e recarrega a prévia; voltar ao código libera a referência na tela e no estado. O teste de integração usa uma imagem de dimensões ímpares, verifica conteúdo, limite de resolução, restauração e bytes originais preservados. A [conferência visual](benchmarks/script-mode/ui-resource-preview-x86_64-api36.json) registra [retrato](assets/script-mode/previa-recurso-retrato-api36.png) e [paisagem com fonte ampliada](assets/script-mode/previa-recurso-paisagem-api36.png) na API 36 x86_64.

## Extensões futuras

Após validar estabilidade e ganho de latência: perfil de consumo, biblioteca reutilizável de rotinas, ferramentas de autoria/diagnóstico mais completas e novas operações versionadas. Checkpoints persistentes, depurador por instrução, marketplace e sincronização em nuvem ficam fora da API 1 atual. O foco desta entrega é latência; otimização de bateria permanece uma etapa posterior.
