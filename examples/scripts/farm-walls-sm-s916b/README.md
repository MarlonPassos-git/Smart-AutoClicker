# Farm e muros no Samsung 2340×1080

Este pacote porta o fluxo de farm/muros para JavaScript local. O perfil usa as imagens e áreas da calibração do SM-S916B de 09/11/2026, em português, na paisagem Clássica e com os muros preparados em fileiras. `provenance.json` identifica a origem e o SHA-256 de cada imagem, preservada sem retoque.

Os testes cobrem decisões e transições com telas determinísticas, além de reconhecimento real de templates, preços, moedas e quantidades nos recortes existentes. A execução completa desta versão JavaScript no jogo e a validação arm64 permanecem pendentes. As melhorias feitas pelo cenário antigo não comprovam este pacote.

## Uso

Inicie com a vila visível, na resolução e no enquadramento do perfil. Confira os parâmetros antes de executar. A calibração reconhece muros de nível 18 e confirma o nível 19 após melhorar. Mudanças de idioma, interface, níveis, paisagem ou posições exigem novas referências.

Da raiz do repositório, com um destino novo:

```sh
python3 scripts/package_script_example.py examples/scripts/farm-walls-sm-s916b build/script-examples/farm-walls-sm-s916b.zip
```

Transfira o ZIP e use **Importar ZIP**. Modelos OCR precisam estar instalados. O pacote usa os módulos e imagens locais durante a execução.

## Comportamento

O encerramento da batalha pode usar a meta de destruição, duas leituras válidas de saque restante iguais a zero ou o fim natural. Saque ilegível não vira zero. O lançamento lê a quantidade positiva de cada card à esquerda dos heróis, distribui o orçamento em blocos de 16, 8, 4, 2 e 1 e confere o ataque entre blocos. O limite é de 600 tentativas de lançamento de tropas; três tentativas completas sem redução da quantidade encerram o fluxo.

Heróis usam referências alternativas, quatro posições de lançamento e ativação da habilidade. Feitiços usam as posições do anel. A pausa conserva o andamento; uma mudança de `resumeGeneration` exige nova leitura/seleção do card. Depois de retornar do resultado, o script espera três segundos para os saldos da vila assentarem.

Os muros são tentados antes da parada por depósitos cheios. O script procura em quatro setores, exige construtor disponível e reconhece os dois preços do muro selecionado. Custos divergentes encerram o fluxo. A preferência de moeda é aplicada primeiro; a alternativa é tentada quando preço ou reserva impedem o recurso preferido.

A seleção de lote cresce até o limite configurado, até o fim da fileira ou até não caber no saldo/reserva. Um clique que não muda o custo não aumenta a contagem. Se o próximo muro deixa o preço insuficiente, o script remove o excedente, confere o total novamente e abre a confirmação. O pagamento exige título, botão, moeda e total reconhecido; a contagem só avança após reconhecer o nível 19. Sem o controle de lote, usa a confirmação unitária, incluindo a cor verde do preço.

Com reserva ativa, o saldo precisa ser legível e cobrir custo mais reserva. Sem reserva, a disponibilidade usa as cores dos preços, pois o OCR do saldo já perdeu dígitos nas capturas deste perfil. A leitura dinâmica do custo não elimina essa limitação do reconhecimento. Sem saldo disponível nos dois recursos, volta ao farm; sem construtor, alvo ou confirmação, para com motivo explícito.

O parâmetro **Máximo de batalhas** usa zero para execução contínua. **Máximo de muros por lote** fica entre 1 e 50. Os demais parâmetros controlam reservas, limites mínimos de saldo, capacidades e gatilhos de entrada/parada. As respostas ficam fora do pacote.

## Organização

- `farm.js`: ciclo da vila, limites e decisão de entrar em batalha ou melhorar muros.
- `battle.js`: navegação, tropas, heróis, feitiços, encerramento e retorno.
- `walls.js`: seleção, moeda, reservas, lotes, pagamento e confirmação.
- `policy.js`: decisões numéricas sem acesso Android.
- `screen.js`: observação, prazos, revalidação e ações protegidas pelo frame.
- `profile.js`, `manifest.json` e `images/`: consultas, parâmetros e calibração visual.

A referência anterior permanece em `scripts/research/configure_farm_scenario.py`; este pacote não transforma JavaScript em eventos. Consulte o [guia do modo Script](../../../docs/MODO-SCRIPT.md) e a [documentação de desenvolvimento](../../../docs/DESENVOLVIMENTO-MODO-SCRIPT.md).
