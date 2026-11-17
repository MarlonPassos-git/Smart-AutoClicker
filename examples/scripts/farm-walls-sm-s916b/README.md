# Farm e muros no Samsung 2340×1080

Este pacote porta o fluxo de farm/muros para JavaScript local. O perfil usa as imagens e áreas da calibração do SM-S916B de 09/11/2026, em português, na paisagem Clássica e com os muros preparados em fileiras. `provenance.json` identifica a origem e o SHA-256 de cada imagem, preservada sem retoque.

Os testes cobrem decisões e transições com telas determinísticas, além de reconhecimento real de templates, preços, moedas e quantidades nos recortes existentes. A [rodada multivilas V3 no Samsung arm64](../../../docs/benchmarks/script-mode/farm-round-v3-sm-s916b.json) confirmou um ataque por conta, troca automática e encerramento, com muros desativados. O ciclo completo com muros desta versão permanece pendente; as melhorias feitas pelo cenário antigo não comprovam este pacote.

A versão 3.1 ajusta o reconhecimento do adversário ao Clash 19.32.4 e inclui o bônus de estrelas: resgata o bônus, abre o baú enquanto há tentativas visíveis, confirma **Continuar** e espera a vila antes do próximo ataque. Também consegue continuar um baú já aberto ou a recompensa já revelada deste perfil. Consulte o [registro de validação do CV19](../../../docs/benchmarks/script-mode/farm-cv19-sm-s916b.json) para os testes realizados e seus limites.

A versão 3.1.1 corrige a identificação da aba **Configurações** e a leitura das capacidades no novo quadro dos depósitos. O cadastro mantém a confirmação por tag e por duas leituras concordantes de cada capacidade. Os quadros anteriores continuam reconhecidos. O [registro de sincronização e farm no CV19](../../../docs/benchmarks/script-mode/farm-sync-cv19-sm-s916b.json) documenta a validação desta correção.

A versão 3.2.4 lê os contadores dos feitiços à direita dos heróis, sem depender da imagem ou do nível do feitiço. Confere a redução após cada bloco de até dois lançamentos, incluindo quantidades ímpares e vários tipos na mesma barra. Os contadores finais `x11`, `x1` e `x0` têm confirmação visual própria; uma leitura ausente não vira zero. A meta de batalha continua tendo prioridade.

A versão 3.2.5 rejeita números do campo sem o prefixo `x` e reconfirma o glifo `x11` durante o lançamento. A identidade é conferida antes do primeiro ataque da vila e após pausa/retomada; ataques consecutivos reaproveitam a autorização da mesma execução. A conferência duplicada antes de uma troca foi removida.

A versão 3.2.6 ajusta a altura do recorte do saldo de elixir negro, cuja borda impedia o OCR da condição de depósitos cheios.

A versão 3.3 exige concordância de pelo menos dois recortes distintos para cada saldo e usa recortes alternativos quando a leitura principal falha. Ausência de leitura não vira zero. Uma falha informa os recursos ilegíveis e distingue esse caso de uma vila não reconhecida. Rodadas limitadas sem melhoria de muros concluem sem reler saldos depois do último ataque confirmado.

A versão 3.4.1 mantém a leitura normal dos saldos e, quando falta concordância, usa o reconhecimento nativo direto de uma linha de inteiros brancos. Ela requer o APK atualizado com `WHITE_INTEGER_LINE`; o novo motor evita a localização de blocos que fragmentava alguns valores.

A versão 3.4.2 espera até três minutos pela busca de oponentes, que ultrapassou um minuto no aparelho. Durante a espera não envia gestos nem confirma um ataque; só prossegue após reconhecer o adversário.

A versão 3.5.0 lança tropas, depois heróis e por último feitiços. Reduz os intervalos entre toques e as esperas dos heróis, preservando as quatro posições de tentativa e a ativação de habilidade. A meta de batalha permanece prioritária. O evento `army_deployment` registra os tempos de tropas, heróis e total antes dos feitiços; 10 segundos é a meta de medição no aparelho, não um prazo que descarte unidades. A [medição no Samsung](../../../docs/benchmarks/script-mode/farm-deployment-3.5.0-sm-s916b.json) concluiu dois ataques em vilas diferentes com 7,93 s e 9,14 s para tropas e heróis, antes dos feitiços. As amostras tinham quatro e dois heróis reconhecidos; seis heróis foram cobertos nos testes determinísticos.

## Uso

Inicie com a vila visível, na resolução e no enquadramento do perfil. Confira os parâmetros antes de executar. A calibração reconhece muros de nível 18 e confirma o nível 19 após melhorar. Mudanças de idioma, interface, níveis, paisagem ou posições exigem novas referências.

Da raiz do repositório, com um destino novo:

```sh
python3 scripts/package_script_example.py examples/scripts/farm-walls-sm-s916b build/script-examples/farm-walls-sm-s916b.zip
```

Transfira o ZIP e use **Importar ZIP**. Modelos OCR precisam estar instalados. O pacote usa os módulos e imagens locais durante a execução.

No APK atualizado, **Tentar novamente** mantém o jogo aberto e inicia uma nova execução da revisão original, com os mesmos parâmetros e progresso, usando a captura ainda ativa. A versão 3.6.0 reconhece batalha e resultado para continuar dali e só confirma a contagem após voltar à conta correta. Se já voltou à vila e o resultado ficou incerto, as escolhas de contagem aparecem no painel flutuante. Se a captura foi encerrada pelo sistema, é necessário parar e conceder uma nova autorização de captura. Uma rodada iniciada em outra revisão deve ser continuada nessa revisão antes de usar o pacote atualizado em uma nova rodada.

## Comportamento

O encerramento da batalha pode usar a meta de destruição, duas leituras válidas de saque restante iguais a zero ou o fim natural. Saque ilegível não vira zero. O lançamento lê a quantidade positiva de cada card à esquerda dos heróis, distribui o orçamento em blocos de 16, 8, 4, 2 e 1 e confere o ataque entre blocos. O limite é de 600 tentativas de lançamento de tropas; três tentativas completas sem redução da quantidade encerram o fluxo.

O lançamento segue tropas, feitiços e heróis. Feitiços usam as posições do anel; tipos que exigem alvos específicos interrompem o fluxo se o contador não diminuir em três tentativas. Heróis usam referências alternativas, quatro posições de lançamento e ativação da habilidade. A pausa conserva o andamento; uma mudança de `resumeGeneration` exige nova leitura/seleção do card. Depois de retornar do resultado, o script espera três segundos para os saldos da vila assentarem.

Os muros são tentados antes da parada por depósitos cheios. O script procura em quatro setores, exige construtor disponível e reconhece os dois preços do muro selecionado. Custos divergentes encerram o fluxo. A preferência de moeda é aplicada primeiro; a alternativa é tentada quando preço ou reserva impedem o recurso preferido.

A seleção de lote cresce até o limite configurado, até o fim da fileira ou até não caber no saldo/reserva. Um clique que não muda o custo não aumenta a contagem. Se o próximo muro deixa o preço insuficiente, o script remove o excedente, confere o total novamente e abre a confirmação. O pagamento exige título, botão, moeda e total reconhecido; a contagem só avança após reconhecer o nível 19. Sem o controle de lote, usa a confirmação unitária, incluindo a cor verde do preço.

Com reserva ativa, o saldo precisa ser legível e cobrir custo mais reserva. Sem reserva, a disponibilidade usa as cores dos preços, pois o OCR do saldo já perdeu dígitos nas capturas deste perfil. A leitura dinâmica do custo não elimina essa limitação do reconhecimento. Sem saldo disponível nos dois recursos, volta ao farm; sem construtor, alvo ou confirmação, para com motivo explícito.

O parâmetro **Máximo de batalhas** usa zero para execução contínua. **Máximo de muros por lote** fica entre 1 e 50. Os demais parâmetros controlam reservas e gatilhos de entrada/parada. As capacidades são descobertas na configuração das vilas. As respostas ficam fora do pacote.

## Organização

- `farm.js`: ciclo da vila, limites e decisão de entrar em batalha ou melhorar muros.
- `battle.js`: navegação, tropas, heróis, feitiços, encerramento e retorno.
- `spells.js`: leitura dos contadores de feitiços, sem reconhecimento de retratos.
- `rewards.js`: resgate do bônus de estrelas, abertura do baú e confirmação da recompensa.
- `walls.js`: seleção, moeda, reservas, lotes, pagamento e confirmação.
- `policy.js`: decisões numéricas sem acesso Android.
- `screen.js`: observação, prazos, revalidação e ações protegidas pelo frame.
- `profile.js`, `manifest.json` e `images/`: consultas, parâmetros e calibração visual.

A referência anterior permanece em `scripts/research/configure_farm_scenario.py`; este pacote não transforma JavaScript em eventos. Consulte o [guia do modo Script](../../../docs/MODO-SCRIPT.md) e a [documentação de desenvolvimento](../../../docs/DESENVOLVIMENTO-MODO-SCRIPT.md).

## Configurar vilas e farmar

Use **Configurar vilas** antes do primeiro farm. Essa ação visita as contas conectadas, confirma suas tags, lê as capacidades de ouro, elixir e elixir negro e retorna à vila inicial. Não inicia ataques. O cadastro fica privado no aparelho e só é substituído depois que toda a configuração termina; uma falha preserva o anterior.

Em **Iniciar**, selecione as vilas salvas e a condição para concluir cada uma: ouro cheio, elixir cheio, elixir negro cheio, qualquer depósito cheio, todos cheios ou ambos os depósitos de ouro/elixir cheios. **Nunca** desativa a conclusão por recursos; em várias vilas, exige limite positivo de batalhas. O mesmo limite vale para cada vila. Selecionar mais de uma desmarca e bloqueia **Melhorar muros**. As opções de muros só aparecem quando essa melhoria está ligada.

Não há capacidades nem quantidades de recursos para digitar. O farm usa a capacidade salva de cada conta e lê os saldos atuais na tela. Saldo igual ou superior à capacidade conta como depósito cheio, inclusive quando uma oferta permite ultrapassar o limite. Para muros, o gatilho de qualquer depósito considera apenas ouro e elixir. Após ampliar depósitos, adicionar contas ou mudar a calibração, execute **Configurar vilas** novamente. Uma nova rodada reutiliza o cadastro; não visita todas as contas para descobri-las outra vez.

A rodada começa na vila aberta, se ela estiver selecionada, e segue pelas demais selecionadas. Nomes localizam itens do seletor; a tag completa confirma a identidade, inclusive com nomes parecidos. Contas removidas, desconectadas ou ambíguas interrompem o fluxo. A linha de login não é selecionada. Depois de concluir todas as vilas selecionadas, a execução encerra.

`configuration.js` coordena o cadastro; `villages.js` concentra descoberta, troca e capacidades; `round.js` mantém a fila e o checkpoint. A contagem avança após confirmar o retorno da batalha. **Continuar rodada** mantém parâmetros, capacidades da rodada e contagem; **Nova rodada** substitui apenas o progresso, preservando o cadastro. Uma batalha interrompida e de resultado incerto exige resolução explícita, sem repetição automática.

O perfil continua específico ao Samsung, português e 2340×1080. Mudanças no seletor, tags, avisos ou regiões exigem recalibração. Os testes determinísticos não substituem a validação no jogo.
