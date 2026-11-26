# Modo Script

O modo Script executa JavaScript no próprio Android e mantém seus pacotes separados dos cenários por imagem e das sequências simples.

## Farm — vilas e muros

A partir da versão 4.0.0-beta07, o farm vem no APK e aparece na tela inicial ao abrir o Klick'r. Toque em **Configurar vilas** para fazer o cadastro. Em **Iniciar**, selecione as vilas e revise o limite de batalhas e as opções de muros antes de confirmar.

O pacote inclui o [perfil de referência do Samsung SM-S916B](../examples/scripts/farm-walls-sm-s916b/README.md), calibrado em português, na paisagem Clássica, a 2340 × 1080. Mudanças de layout, idioma ou enquadramento podem exigir recalibração. Se você já importou esse pacote, o Klick'r mantém a sua revisão e seus dados.

## Criar, importar e executar

Os scripts importados aparecem diretamente na tela inicial. Toque no cartão ou no botão de iniciar para abrir os parâmetros da revisão exibida. A biblioteca mostra uma entrada por pacote; revisões anteriores continuam guardadas para preservar execuções e progresso.

1. Para importar ou editar um pacote, abra o menu da lista de cenários e escolha **Scripts**.
2. Use **Criar script** ou **Importar ZIP**. O manifesto fica na raiz do ZIP.
3. Revise os arquivos. Selecionar um PNG mostra uma prévia e suas dimensões originais. O editor aceita JavaScript e JSON, mostra números de linha e preserva o rascunho durante a rotação. **Salvar** cria outra revisão; **Validar** compila sem executar operações Android.
4. Em **Capturar recurso**, informe um nome, escolha imagem com região ou somente região e autorize a captura. Abra a tela alvo, ajuste a área e confirme. O aplicativo salva outra revisão e volta ao editor. Cancelar conserva a revisão anterior.
5. Toque em **Iniciar**, revise os parâmetros e conceda as permissões solicitadas. A execução usa a revisão salva e as respostas conferidas. Se o pacote declarar `targetPackage`, o aplicativo alvo abre automaticamente após a autorização da captura; isso também vale para configurar vilas e capturar recursos.
6. No controle flutuante, acompanhe a etapa, pause, retome ou pare. O diagnóstico mostra as mensagens retidas e a causa do encerramento.

Captura de tela, sobreposição e acessibilidade precisam estar disponíveis. Texto e números também exigem os modelos OCR correspondentes instalados. A execução usa arquivos e módulos locais; não precisa de computador conectado, Node, npm ou serviços de nuvem.

Os valores recentes dos parâmetros ficam no aplicativo, separados dos padrões do pacote. Logs, rascunhos e sessões em memória também ficam fora do ZIP exportado. A exportação contém os arquivos da revisão selecionada.

## Pausa e parada

Durante a execução, o painel estreito mostra a mensagem da etapa em uma linha e mantém Pausar e Parar visíveis. Toque na mensagem para expandir os controles e acessar Diagnóstico e Mover; toque novamente para recolher. Feche o histórico antes de recolher o painel. Em pausa ou erro, os controles se expandem automaticamente e exibem a mensagem completa. Posicione os controles fora das áreas de leitura e toque do script. Se um toque do script atingir o painel, ele será bloqueado e a sessão solicitará pausa. Mova os controles e retome para conferir novamente a tela; o toque bloqueado não é repetido automaticamente.

A pausa ocorre no próximo ponto da API de automação. Um bloco de `tapSequence` ou `readAll` pode terminar antes de a sessão entrar em **Pausado**. A sessão libera os ponteiros mantidos e descarta os frames anteriores.

O script declara a conferência de retomada com `resumeWhen(query)`. Ao retomar, o aplicativo captura outra observação e exige que essa condição seja encontrada. Sem uma conferência declarada ou com uma tela incompatível, encerra com diagnóstico. A conferência deve identificar a etapa atual; detectar uma cor ou texto genérico pode aceitar telas demais.

`observe()` devolve `resumeGeneration`. Quando esse valor muda, descarte também decisões sobre seleção de cards ou outros estados da interface mantidos entre blocos. As funções `waitAny` e `waitFor` repetem a observação quando uma pausa invalida um frame.

**Parar** cancela esperas, comandos pendentes e JavaScript ocupado. Um gesto já entregue ao Android aguarda o resultado ou timeout do executor antes da liberação dos ponteiros; cancelar a corrotina não retira esse gesto do sistema. Por isso, um gesto longo pode prolongar o estado “Parando”. A sessão não aceita comandos seguintes e aguarda a liberação dos recursos antes de permitir outro modo. Girar a tela durante execução encerra o script com motivo explícito; inicie novamente na orientação calibrada. Durante calibração, a captura acompanha a nova orientação.

A pausa conserva a pilha enquanto a sessão está viva. Fechar o processo ou reiniciar o aparelho encerra esse estado.

## Pacote de formato 1

Exemplo com um módulo de entrada, imagem nomeada, região, posição e parâmetro:

```text
manifest.json
entry.js
images/ready.png
```

```json
{
  "formatVersion": 1,
  "apiVersion": 1,
  "id": "exemplo-botao",
  "name": "Exemplo de botão",
  "scriptVersion": "1.0.0",
  "entry": "entry.js",
  "parameters": {
    "enabled": { "type": "boolean", "label": "Permitir clique", "default": false }
  },
  "resources": {
    "ready": { "type": "image", "path": "images/ready.png", "width": 80, "height": 40 }
  },
  "screen": {
    "width": 1080,
    "height": 2400,
    "regions": {
      "ready": { "left": 400, "top": 1000, "right": 600, "bottom": 1100 }
    },
    "positions": { "confirm": { "x": 500, "y": 1050 } }
  }
}
```

`formatVersion` identifica o formato do arquivo; `apiVersion`, a API de automação; `scriptVersion`, a versão atribuída pelo autor. `id` aceita de 1 a 80 letras minúsculas, números, pontos e hífens, começando por letra ou número. Caminhos são relativos ao pacote, com `/`, sem segmentos vazios, `.` ou `..`, barras invertidas ou caminhos absolutos. O módulo `klickr` é reservado.

Em recursos de imagem, `width` e `height` são as dimensões esperadas **do recorte**, em pixels da resolução de referência. `screen.width` e `screen.height` descrevem a tela usada na calibração. O perfil ajusta regiões, posições e dimensões das imagens quando a resolução muda com a mesma proporção, com tolerância de 1%. Outra proporção exige recalibração. Escalonar coordenadas não garante que outro aparelho mantenha o mesmo layout ou reconhecimento.

As regiões usam limites esquerdo/superior inclusivos e direito/inferior exclusivos. Nomes de regiões e posições aceitam letras, números e `_`, começando por uma letra, até 64 caracteres. Cada grupo admite até 256 nomes. O perfil é opcional para scripts que usam coordenadas absolutas; `profile()` exige um perfil presente e compatível.

### Recursos de reconhecimento

| Tipo | Definição no manifesto |
| --- | --- |
| Imagem | `{"type":"image","path":"images/ready.png","width":80,"height":40}` |
| Texto esperado | `{"type":"text","alternatives":["Pronto","Continuar"],"model":"LATIN"}` |
| Cor média | `{"type":"color","argb":-1}` |
| Formato numérico | `{"type":"number","format":"COMMA_DECIMAL"}` |

Números aceitam `AUTO`, `DOT_DECIMAL`, `COMMA_DECIMAL`, `POSITIVE_QUANTITY` e `WHITE_INTEGER_LINE`. `POSITIVE_QUANTITY` reconhece quantidades positivas com prefixo `x`, preservando a posição da leitura. `WHITE_INTEGER_LINE` é destinado a um campo calibrado com uma única linha de inteiros não negativos em branco, sobre fundo escuro ou colorido. Ele reconhece diretamente a linha, aceita zero e separadores de grupos de três dígitos; exige um APK que inclua esse formato. O recurso de texto procura alternativas esperadas. Para extrair blocos de texto livre da observação, use `readText()`.

### Parâmetros

| Tipo | Campos |
| --- | --- |
| Número | `type: "number"`, `label`, `default`; `min` e `max` opcionais |
| Booleano | `type: "boolean"`, `label`, `default` booleano |
| Escolha | `type: "choice"`, `label`, `default` e `choices` com strings distintas |

Os números precisam ser finitos. Uma escolha precisa pertencer à lista declarada. Há até 64 parâmetros; as respostas são validadas antes da execução e não alteram os padrões do manifesto.

### Limites de importação e memória

| Recurso | Limite |
| --- | --- |
| Entradas no ZIP | 128 |
| Arquivo extraído | 2 MiB |
| Pacote extraído | 16 MiB |
| Manifesto ou arquivo JavaScript | 256 KiB por arquivo |
| Imagem declarada | 16 milhões de pixels |
| Templates decodificados na sessão | 64 MiB no total |
| Heap do interpretador | 32 MiB |
| Pilha do interpretador | 256 KiB |
| JavaScript continuamente ocupado | 5 segundos |

Importações passam pela validação antes da publicação da revisão. Uma falha conserva as revisões anteriores. O limite do interpretador não inclui bitmaps, modelos OCR ou outras alocações Android/nativas. Os prazos de espera de uma operação são separados da duração total da sessão.

## API JavaScript 1

Importe as operações do módulo local `klickr`:

```js
import { observe, profile, read, tap, parameters, resumeWhen, stop } from "klickr";

const options = await parameters();
const observation = await observe();
const screen = await profile(observation);
const query = { kind: "image", reference: "ready", threshold: 12, region: screen.regions.ready };
await resumeWhen(query);
const result = await read(observation, query);
if (result.status === "found" && options.enabled) await tap(result);
await stop("Conferência concluída.");
```

Uma pausa entre `observe` e `read` pode invalidar o frame. O exemplo [com recuperação](../examples/scripts/generic-recovery/entry.js) trata `stale_observation`, observa novamente e limita as tentativas. Reutilize esse tratamento nas rotinas que agrupam leituras.

### Observação e reconhecimento

| Operação | Resultado e contrato |
| --- | --- |
| `observe()` | `frame`, `monotonicNanos`, `resumeGeneration`, `width`, `height`. Substitui a observação anterior. |
| `profile(observation)` | Regiões e posições escaladas para a observação. As posições carregam seu `frame`. |
| `read(observation, query)` | Lê o mesmo bitmap, sem nova captura. `query` contém `kind`, `region`, `threshold` e `reference`. |
| `readAll(observation, queries)` | Lê de 1 a 64 consultas no mesmo bitmap, com uma travessia da ponte. Devolve um array imutável de resultados na ordem das consultas. |
| `parameters()` | Objeto com as respostas validadas para esta execução. |
| `now()` | Milissegundos de relógio monotônico para prazos de operação, descontando pausa e revalidação. |

`kind` aceita `image`, `text`, `number` ou `color`. `reference` identifica um recurso do manifesto; é opcional em números, cujo padrão é `AUTO`. `threshold` fica entre 0 e 100. Em texto/números representa confiança mínima. Em imagem/cor representa tolerância: aumentar o valor torna a condição menos restritiva.

Os resultados de `read` distinguem `found`, `not_found` e `invalid_reading`. Uma leitura numérica inválida não vira zero. Quando disponíveis, incluem `x`, `y`, `width`, `height`, `number`, `confidence` e o `frame` usado. A posição é o centro do resultado; use as dimensões para calcular bordas.

`readAll` valida a estrutura de todas as consultas antes de reconhecer a primeira. Cada resultado mantém seu status e o mesmo `frame`; o lote não interrompe no primeiro `not_found`. A pausa ocorre antes ou depois da chamada, e parar cancela as leituras ainda pendentes. Use grupos pequenos para permitir pausas frequentes. Um frame invalidado faz o lote falhar com `stale_observation`; observe novamente antes de repetir. Leituras em lote mantêm regiões, limiares e qualidade e não reutilizam resultados de outro frame.

Uma nova observação, um gesto ou uma pausa invalida os resultados anteriores. Usar seu frame produz um erro com `code === "stale_observation"`. Não remova o campo `frame` de um resultado reconhecido para contornar a conferência.

### Gestos e esperas

| Operação | Uso |
| --- | --- |
| `tap(position, durationMs = 50)` | Toque em coordenadas inteiras ou em um resultado reconhecido. |
| `swipe(from, to, durationMs = 300)` | Deslize entre duas posições. O frame de `from`, quando informado, é conferido. |
| `zoom(center, direction, intensityPx = 150, durationMs = 300)` | Zoom com `direction` igual a `"in"` ou `"out"`; intensidade de 1 a 10000 pixels. |
| `gesture(fingers, observation?)` | Até 10 dedos, 64 movimentos por dedo e 60000 ms por dedo. |
| `tapSequence(steps, observation)` | Até 64 toques com duração configurada total de até 1000 ms; valida o bloco inteiro antes de começar. |
| `sleep(durationMs)` | Espera cancelável de 1 a 60000 ms. |
| `waitFor(query, {timeoutMs, intervalMs = 10})` | Aguarda uma condição e retorna a leitura encontrada ou `timeout` com a última leitura. |
| `waitAny(alternatives, {timeoutMs, intervalMs = 10})` | Aguarda alternativas nomeadas, lendo todas no mesmo frame de cada rodada. Retorna a primeira encontrada. |
| `retry(operation, {attempts, intervalMs = 10})` | Repete até o limite; termina em `found` ou `success`. Erros inesperados são propagados. |

Aguardar o retorno de um gesto confirma o retorno do executor Android; o aplicativo alvo ainda pode processar os eventos e desenhar depois. Use uma condição de tela, como `waitFor`, para confirmar o resultado antes da próxima ação.

`tap`, `swipe` e `zoom` aceitam duração de 1 a 60000 ms. Os intervalos de espera/repetição vão até 60000 ms. `timeoutMs`, `intervalMs` e `attempts` precisam ser inteiros positivos. Esperas usam prazo monotônico; uma leitura concluída depois do prazo retorna timeout.

Um dedo de `gesture` tem `start: {x,y}`, `startDelayMs`, `holdMs`, `steps` e `keepDown`. Cada movimento tem `durationMs` e `to: {x,y}`; omitir `to` representa espera no mesmo ponto. Só o primeiro dedo pode continuar pressionado. Ponteiros mantidos e esperas estacionárias entre movimentos exigem API 26; na API 24/25, use a pressão inicial `holdMs`. Pausa e parada liberam ponteiros mantidos.

Um passo de `tapSequence` tem `at: {x,y}`, `pressMs` de 1 a 1000 e `afterMs` de 0 a 1000. A soma de todas as pressões e intervalos fica limitada a 1000 ms. A pausa cooperativa ocorre antes ou depois do bloco; uma parada cancela os toques ainda pendentes.

```js
const outcome = await waitAny([
  { name: "ready", query: readyQuery },
  { name: "dialog", query: closeDialogQuery }
], { timeoutMs: 10000, intervalMs: 50 });

if (outcome.status === "found" && outcome.name === "dialog") {
  await tap(outcome.result);
}
```

### Etapas, diagnóstico e encerramento

- `stage(name, message = "")`: registra a etapa/rotina e sua mensagem. Nome até 128 caracteres; mensagem até 4096.
- `log(message)`: acrescenta uma mensagem de até 4096 caracteres ao diagnóstico.
- `resumeWhen(query)`: declara a condição que precisa ser reconhecida em uma captura nova ao retomar.
- `stop(message)`: encerra a sessão com motivo explícito e impede os comandos seguintes.

O painel conserva até 200 eventos em memória. Além dele, o celular salva um histórico privado das últimas 20 execuções, com revisão, horário, etapas, operações, resultado, motivo de parada e erro com pilha. Cada execução retém os dois segmentos mais recentes de até 512 KiB cada e o último checkpoint, quando o pacote oferece progresso persistente. Não há depurador por instrução nem inspeção de todas as variáveis locais.

Abra **Diagnóstico detalhado** no controle flutuante para consultar as operações, seus tempos e resultados. O registro persistente continua quando o painel está fechado. A etapa e a mensagem de espera continuam nos controles principais. O histórico visível atualiza até cinco vezes por segundo e formata as linhas conforme aparecem no painel. A lista acompanha os eventos novos quando está no fim; ao consultar eventos anteriores, sua posição de leitura é preservada.

Eventos de operação acrescentam `operation`, `durationNanos`, `outcome` e `details`. O resultado da chamada (`outcome`) e o status do reconhecimento são distintos: uma operação pode concluir normalmente com `invalid_reading`. Os detalhes incluem frame, referência, região e leitura numérica quando disponíveis; lotes resumem as contagens e até oito números. Perfis são resumidos por dimensões e quantidade de regiões/posições. Na operação `parameters`, o detalhamento automático registra a quantidade de parâmetros, preservando as respostas fora desses metadados. O conteúdo de `log` continua sob controle do autor.

## Exemplos e empacotamento

O [exemplo genérico](../examples/scripts/generic-recovery/manifest.json) demonstra condição, repetição limitada, espera, recuperação de diálogo e confirmação após ação. Capture `ready`, `completed` e `closeDialog` no aplicativo alvo. O padrão apenas observa; o parâmetro **Permitir cliques** habilita a ação.

O [farm/muros](../examples/scripts/farm-walls-sm-s916b/manifest.json) usa o perfil Samsung 2340×1080 em português, com imagens da calibração de 09/11/2026. Inclui modos de encerramento, lançamento por quantidade, heróis, feitiços, volta à vila, reservas, prioridade/troca de moeda, lotes e conferência da melhoria. O pacote abre o Clash of Clans automaticamente. A vila precisa estar acessível para o reconhecimento prosseguir; telas de atualização, login ou carregamento podem exigir intervenção. Resolução, idioma, enquadramento, níveis e referências precisam corresponder ao perfil. Os testes determinísticos e de reconhecimento não comprovam uma execução completa deste script no jogo.

Para preparar os ZIPs no computador, com destinos novos:

```sh
python3 scripts/package_script_example.py examples/scripts/generic-recovery build/script-examples/generic-recovery.zip
python3 scripts/package_script_example.py examples/scripts/farm-walls-sm-s916b build/script-examples/farm-walls-sm-s916b.zip
```

Transfira o ZIP ao Android e use **Importar ZIP**. O empacotador não executa JavaScript e recusa substituir arquivos existentes. O aplicativo faz a validação do formato ao importar.

A [documentação de desenvolvimento](DESENVOLVIMENTO-MODO-SCRIPT.md) descreve módulos, extensão da API e verificações.

## Rodadas recuperáveis e regras condicionais

O manifesto pode declarar `"executionState": {"version": 1}` para habilitar um checkpoint privado por `id` do script. `loadState()` lê esse objeto; `saveState(objeto, {completed: false})` grava atomicamente até 256 KiB. O estado fica separado do pacote e das respostas recentes. Uma execução mantém a posse exclusiva do registro. Exportar o ZIP não inclui o progresso.

Na biblioteca, uma rodada incompleta oferece **Continuar rodada** e **Nova rodada**. Continuar exige a mesma revisão imutável e conserva as respostas originais. Outra revisão, uma calibração ou um registro inválido exige a escolha explícita de uma nova rodada. Cancelar conserva o registro. Não há migração automática de progresso entre versões.

O script define a versão e o conteúdo do seu estado. Grave a tentativa antes de efeitos externos e a confirmação depois de observar o resultado. Um fechamento entre esses pontos pode deixar resultado incerto. Para pedir uma resolução explícita no próximo início, o checkpoint pode incluir:

```json
{"version":1,"recovery":{"message":"Confira o resultado antes de continuar.","choices":[{"id":"confirmed","label":"Confirmar resultado"},{"id":"not-performed","label":"Confirmar que não ocorreu"}]}}
```

A biblioteca grava a escolha em `recovery.answer`, sem interpretar o domínio do script. O script deve validar e consumir essa resposta. Checkpoints não garantem execução exatamente uma vez no aplicativo externo.

Regras opcionais `parameterRules` complementam a validação individual. Este formato delimitado não implementa JSON Schema:

```json
[
  {"when":{"multiple":true},"forceFalse":"walls","message":"Muros indisponíveis neste modo."},
  {"when":{"multiple":true},"requireAny":[{"parameter":"limit","greaterThan":0},{"parameter":"stop","notEquals":"nunca"}],"message":"Escolha limite ou condição de parada."}
]
```

`when` compara parâmetros booleanos declarados ou a quantidade de itens de uma seleção, como `{"villages":{"minItems":2}}`. `forceFalse` desmarca e bloqueia um parâmetro booleano, com explicação visível; desligar a condição não o reativa. `requireAny` exige ao menos uma comparação `equals`, `notEquals` ou `greaterThan`. A validação final rejeita respostas incompatíveis mesmo fora da interface. Pacotes sem regras mantêm o comportamento anterior.

### Texto livre e progresso

```js
import { observe, readText, progress } from "klickr";
const frame = await observe();
const text = await readText(frame, {
  region: {left: 100, top: 100, right: 500, bottom: 300},
  model: "LATIN", threshold: 85, maxResults: 32
});
await progress("Vila 1/2 · 3/10 ataques");
```

`readText()` retorna `frame`, `blocks` e `truncated`. Cada bloco contém `text`, confiança OCR de 0 a 100, limites absolutos e o vínculo ao frame. A região admite até 1.048.576 pixels e o resultado, até 64 blocos com 1.024 caracteres cada. `truncated` é conservador: verdadeiro quando o limite é alcançado. Uma pausa invalida esses blocos para efeitos posteriores; observe novamente. O OCR roda na thread do detector, com os modelos instalados, e não expõe acesso ao Android ou ao filesystem.

`progress()` mantém uma mensagem separada das etapas transitórias, visível também no painel compacto. Use `log()` para os motivos e detalhes por item. O histórico continua limitado; não há arquivo ilimitado de rodadas.

O menu Smart também separa Pausar/Retomar de Parar. Pausar mantém o processador, os contadores e a posição na sequência; suspende esperas e libera ponteiros. Retomar captura novamente e verifica as condições do evento antes do próximo efeito. Uma tela incompatível encerra o processamento. Um gesto já despachado pode terminar antes da pausa.

## Configuração independente

Pacotes podem declarar `"setup":{"entry":"setup.js","label":"Configurar vilas"}` junto com `executionState`. A biblioteca e o card da tela inicial mostram essa ação separada de **Iniciar**. Ela executa o arquivo declarado sem abrir as perguntas do farm e sem alterar respostas recentes ou progresso de rodadas.

`loadConfiguration()` lê o cadastro privado do pacote. `saveConfiguration(objeto)` grava atomicamente até 256 KiB e só pode ser usado pela entrada de configuração. O arquivo fica separado do checkpoint, isolado por `id`; novas rodadas e revisões não o apagam. Exportar ZIP não inclui esse cadastro. O script deve validar sua versão e compatibilidade com o perfil antes de usá-lo.

O parâmetro `{"type":"selection","label":"Vilas para farmar","default":[]}` apresenta os itens de `configuration.items`, que devem ter `id` único e `label` não vazio, em um cadastro com `formatVersion: 1`. A resposta é uma lista de IDs únicos. Antes de iniciar, o app exige ao menos um item ainda disponível no cadastro. Condições `visibleWhen`, como `{"improveWalls":true}`, escondem opções que dependem de um booleano desativado; as respostas continuam passando pela validação normal.

Nos exemplos de farm V3, **Configurar vilas** salva tags e capacidades dos três recursos. **Iniciar** oferece a seleção das vilas e a condição de conclusão. Várias vilas desativam muros. Os saldos são lidos durante o farm; as capacidades não são digitadas. Atualize a configuração depois de ampliar depósitos ou alterar as contas conectadas. Rodadas da V2 permanecem associadas à revisão original; a V3 exige uma nova rodada e sua configuração inicial.

A [validação do farm V3 no Samsung](benchmarks/script-mode/farm-round-v3-sm-s916b.json) concluiu uma batalha em cada vila, confirmou a troca automática de conta e encerrou a rodada, com muros desativados. O teste usou limite de uma batalha por vila e parada por recursos em **Nunca**; não foi uma execução contínua até encher depósitos.


### Abrir o aplicativo e exportar o histórico

O campo opcional `"targetPackage":"com.supercell.clashofclans"` no manifesto identifica o aplicativo Android que deve abrir. Pacotes antigos, sem esse campo, continuam usando o aplicativo que estiver visível. Se o aplicativo declarado estiver ausente ou desabilitado, a execução não começa e a tentativa fica registrada. A autorização de captura do Android continua necessária. A preparação aguarda até 30 segundos pela orientação calibrada; o próprio script continua responsável por reconhecer a tela antes de agir.

Na **Biblioteca de scripts**, abra o menu **⋮ → Exportar histórico** e escolha onde salvar `klickr-historico.zip`. O ZIP contém uma pasta por execução:

- `summary.json`: identificação da revisão, dispositivo, horários, resultado (`COMPLETED`, `STOPPED`, `FAILED` ou `INTERRUPTED`), motivo e erro.
- `checkpoint.json`: último progresso salvo pelo script. O campo `answers` do envelope é omitido; o próprio progresso pode conter opções copiadas pelo script. No farm, conserva a vila atual, ataques confirmados e ataque pendente. Uma pendência representa uma tentativa sem confirmação, não um ataque concluído.
- `events.previous.jsonl` e `events.jsonl`: eventos em ordem, com etapa, operação, resultado e metadados limitados. O segundo arquivo é o mais recente. Saldos e confiança de leitura ajudam a investigar falhas; imagens da tela e código-fonte não são incluídos automaticamente.

As gravações de eventos usam uma fila de até 512 itens, fora da execução de gestos; eventual descarte aparece em `droppedEvents`. Checkpoints e resumos são salvos atomicamente. Um encerramento abrupto pode perder os últimos eventos ainda na fila ou deixar a última linha incompleta. O próximo início ou a exportação identifica execuções de outro processo que ficaram sem encerramento. Em Android 11 ou superior, acrescenta a causa do fim do processo quando o sistema ainda disponibiliza esse registro, incluindo indicação de crash Java ou nativo. Sem essa evidência, registra `INTERRUPTED` sem afirmar que houve crash. Um travamento sem encerramento do processo permanece em execução até ser interrompido.

Os arquivos ficam no armazenamento privado `files/scripts/execution-logs/`, sobrevivem ao fechamento do aplicativo e às atualizações, e são removidos ao limpar os dados ou desinstalar. A retenção é automática: até 20 execuções encerradas/recentes, aproximadamente 26 MiB no total com os limites padrão. Textos e checkpoints escritos pelo script podem conter nomes de vilas e outros dados próprios da automação; revise o ZIP antes de compartilhar.
