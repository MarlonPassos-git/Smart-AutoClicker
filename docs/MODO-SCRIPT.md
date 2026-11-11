# Modo Script

O modo Script executa JavaScript no próprio Android e mantém seus pacotes separados dos cenários por imagem e das sequências simples. A interface está disponível nos builds deste checkout. A validação do farm completo em aparelho arm64 e a comparação final de latência ainda estão pendentes; este documento não anuncia uma versão publicada.

## Criar, importar e executar

1. Na lista de cenários, abra o menu e escolha **Scripts**.
2. Use **Criar script** ou **Importar ZIP**. O manifesto fica na raiz do ZIP.
3. Revise os arquivos. Selecionar um PNG mostra uma prévia e suas dimensões originais. O editor aceita JavaScript e JSON, mostra números de linha e preserva o rascunho durante a rotação. **Salvar** cria outra revisão; **Validar** compila sem executar operações Android.
4. Em **Capturar recurso**, informe um nome, escolha imagem com região ou somente região e autorize a captura. Abra a tela alvo, ajuste a área e confirme. O aplicativo salva outra revisão e volta ao editor. Cancelar conserva a revisão anterior.
5. Toque em **Iniciar**, revise os parâmetros e conceda as permissões solicitadas. A execução usa a revisão salva e as respostas conferidas.
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

Números aceitam `AUTO`, `DOT_DECIMAL`, `COMMA_DECIMAL` e `POSITIVE_QUANTITY`. O último reconhece quantidades positivas com prefixo `x`, preservando a posição da leitura. Texto procura as alternativas esperadas; não extrai texto arbitrário da tela.

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

O histórico conserva até 200 eventos em memória. O formato interno usa JSON com `kind`, `stage`, `message` e `monotonicNanos`; a interface mostra texto legível. Erros preservam arquivo, linha, coluna, causa e pilha quando fornecidos pelo interpretador. O diagnóstico termina com a sessão; não há checkpoint persistente, depurador por instrução ou inspeção de todas as variáveis locais.

Abra **Diagnóstico detalhado** no controle flutuante para registrar também as operações iniciadas, seus tempos e resultados. Fechar o painel ou ocultar o controle desativa esse detalhamento. A etapa e a mensagem de espera continuam nos controles principais. O histórico visível atualiza até cinco vezes por segundo e formata as linhas conforme aparecem no painel. A lista acompanha os eventos novos quando está no fim; ao consultar eventos anteriores, sua posição de leitura é preservada.

Eventos de operação acrescentam `operation`, `durationNanos`, `outcome` e `details`. O resultado da chamada (`outcome`) e o status do reconhecimento são distintos: uma operação pode concluir normalmente com `invalid_reading`. Os detalhes incluem frame, referência, região e leitura numérica quando disponíveis; lotes resumem as contagens e até oito números. Perfis são resumidos por dimensões e quantidade de regiões/posições. Na operação `parameters`, o detalhamento automático registra a quantidade de parâmetros, preservando as respostas fora desses metadados. O conteúdo de `log` continua sob controle do autor.

## Exemplos e empacotamento

O [exemplo genérico](../examples/scripts/generic-recovery/manifest.json) demonstra condição, repetição limitada, espera, recuperação de diálogo e confirmação após ação. Capture `ready`, `completed` e `closeDialog` no aplicativo alvo. O padrão apenas observa; o parâmetro **Permitir cliques** habilita a ação.

O [farm/muros](../examples/scripts/farm-walls-sm-s916b/manifest.json) usa o perfil Samsung 2340×1080 em português, com imagens da calibração de 09/11/2026. Inclui modos de encerramento, lançamento por quantidade, heróis, feitiços, volta à vila, reservas, prioridade/troca de moeda, lotes e conferência da melhoria. Inicie com a vila visível. Resolução, idioma, enquadramento, níveis e referências precisam corresponder ao perfil. Os testes determinísticos e de reconhecimento não comprovam uma execução completa deste script no jogo.

Para preparar os ZIPs no computador, com destinos novos:

```sh
python3 scripts/package_script_example.py examples/scripts/generic-recovery build/script-examples/generic-recovery.zip
python3 scripts/package_script_example.py examples/scripts/farm-walls-sm-s916b build/script-examples/farm-walls-sm-s916b.zip
```

Transfira o ZIP ao Android e use **Importar ZIP**. O empacotador não executa JavaScript e recusa substituir arquivos existentes. O aplicativo faz a validação do formato ao importar.

A [documentação de desenvolvimento](DESENVOLVIMENTO-MODO-SCRIPT.md) descreve módulos, extensão da API e verificações.
