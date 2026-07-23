# Catálogo de tropas e referências visuais

Fonte: asset pack do Clash of Clans `18.400.9`

Idioma cruzado: português

Catálogo completo local: `.local-research/clash-of-clans/18.400.9/catalog/`

Ícones separados localmente: `.local-research/clash-of-clans/18.400.9/icons/`

Processo de extração:
[icon-extraction-process.md](icon-extraction-process.md)

## Catálogo visual completo

Extração sem filtro de gameplay: inclui unidades normais, supertropas, Base do
Construtor, máquinas de cerco, unidades defensivas ou invocadas, eventos,
variantes sazonais e registros deprecated que ainda possuem `IconExportName`.

| Origem | Exports de ícone únicos | Linhas/aliases preservados |
| --- | ---: | ---: |
| Tropas e variantes (`characters.csv`) | 100 | 146 |
| Heróis (`heroes.csv`) | 8 | 8 |
| Pets e variantes (`pets.csv`) | 16 | 18 |
| Feitiços e variantes (`spells.csv`) | 26 | 38 |
| Total sem duplicar exports compartilhados | 148 | 210 |

### Tropas e todas as variantes

![100 ícones separados de tropas e suas variantes](../../../.local-research/clash-of-clans/18.400.9/icons/troops.png)

### Heróis

![8 ícones separados de heróis](../../../.local-research/clash-of-clans/18.400.9/icons/heroes.png)

### Pets e variantes

![16 ícones separados de pets e suas variantes](../../../.local-research/clash-of-clans/18.400.9/icons/pets.png)

### Feitiços e todas as variantes

![26 ícones separados de feitiços e suas variantes](../../../.local-research/clash-of-clans/18.400.9/icons/spells.png)

Cada PNG individual está em `icons/by-export/`. Cópias organizadas por domínio e
categoria estão em `icons/troops/`, `icons/heroes/`, `icons/pets/` e
`icons/spells/`. `icons/manifest.json` preserva nomes internos, TIDs, nomes em
português e todos os aliases que compartilham o mesmo visual.

Alguns exports aparecem em mais de uma tabela. Exemplo:
`icon_unit_pet_phoenix` representa a Fênix em `pets.csv` e também é referenciado
por feitiços internos de reanimação. Por isso, soma das quatro folhas é 150,
enquanto conjunto deduplicado contém 148 PNGs.

Essas imagens são locais e proprietárias; links renderizam apenas em checkout
que possui `.local-research/`. Arquivos não entram no Git.

## Referências técnicas adicionais

### Texturas de personagens

![Texturas de personagens do Clash of Clans](../../../.local-research/clash-of-clans/18.400.9/decoded/character-textures-contact-sheet.png)

### Estrutura visual dos cards de unidades

![Fundos e componentes dos cards de unidades](../../../.local-research/clash-of-clans/18.400.9/decoded/unit_icons/unit_icons_000.png)

## Vila principal

| Grupo | Nome interno | Nome em português | Quartel | Espaço | Export do ícone |
| --- | --- | --- | ---: | ---: | --- |
| Elixir | Barbarian | Bárbaro | 1 | 1 | `icon_unit_barbarian` |
| Elixir | Archer | Arqueira | 2 | 1 | `icon_unit_archer` |
| Elixir | Giant | Gigante | 3 | 5 | `icon_unit_giant` |
| Elixir | Goblin | Goblin | 4 | 1 | `icon_unit_goblin` |
| Elixir | Wall Breaker | Destruidor de Muros | 5 | 2 | `icon_unit_wallbreaker` |
| Elixir | Balloon | Balão | 6 | 5 | `icon_unit_balloon` |
| Elixir | Wizard | Mago | 7 | 4 | `icon_unit_wizard` |
| Elixir | Healer | Curadora | 8 | 14 | `icon_unit_healer` |
| Elixir | Dragon | Dragão | 9 | 20 | `icon_unit_dragon` |
| Elixir | PEKKA | P.E.K.K.A | 10 | 25 | `icon_unit_pekka` |
| Elixir | Baby Dragon | Bebê Dragão | 11 | 10 | `icon_unit_babydragon` |
| Elixir | Miner | Mineiro | 12 | 6 | `icon_unit_miner` |
| Elixir | Electro Dragon | Dragão Elétrico | 13 | 30 | `icon_unit_lightningDragon` |
| Elixir | Yeti | Yeti | 14 | 18 | `icon_unit_yeti` |
| Elixir | Dragon Rider | Dragão Dirigível | 15 | 25 | `icon_unit_dragon_rider` |
| Elixir | Electro Titan | Titã Elétrica | 16 | 32 | `icon_unit_electrotitan` |
| Elixir | Root Rider | Poderosa Hera | 17 | 20 | `icon_unit_root_rider` |
| Elixir | Thrower | Ciclope | 18 | 16 | `icon_unit_thrower` |
| Elixir | Meteor Golem | Golem Meteoro | 19 | 40 | `icon_unit_splitgolem` |
| Elixir Negro | Minion | Servo | 1 | 2 | `icon_unit_gargoyle` |
| Elixir Negro | Hog Rider | Corredor | 2 | 5 | `icon_unit_boarRider` |
| Elixir Negro | Valkyrie | Valquíria | 3 | 8 | `icon_unit_warriorGirl` |
| Elixir Negro | Golem | Golem | 4 | 30 | `icon_unit_golem` |
| Elixir Negro | Witch | Bruxa | 5 | 12 | `icon_unit_witch` |
| Elixir Negro | Lava Hound | Lava Hound | 6 | 30 | `icon_unit_tiny` |
| Elixir Negro | Bowler | Lançador | 7 | 6 | `icon_unit_troll` |
| Elixir Negro | Ice Golem | Golem de Gelo | 8 | 15 | `icon_unit_iceGolem` |
| Elixir Negro | Headhunter | Caçadora de Heróis | 9 | 6 | `icon_unit_headhunter` |
| Elixir Negro | Apprentice Warden | Guardião Aprendiz | 10 | 20 | `icon_unit_apprentice` |
| Elixir Negro | Druid | Druida | 11 | 16 | `icon_unit_druid_bear` |
| Elixir Negro | Furnace | Fornalha | 12 | 18 | `icon_unit_furnace` |

Esses 31 registros foram selecionados antes do bloco de supertropas, com `ProductionBuilding` igual a `Barracks` ou `Dark Barracks`. Unidades secundárias, defensivas e invocadas não entram na contagem.

## Outras categorias

| Categoria | Critério |
| --- | --- |
| Supertropas | `EnabledBySuperLicence = TRUE` e ícone definido |
| Máquinas de cerco | `ProductionBuilding = Siege Workshop` e não deprecated |
| Base do Construtor | `ProductionBuilding = BB Barracks` e não deprecated |
| Heróis | `heroes.csv`, excluindo `VillageType = 1` |
| Pets | `pets.csv`, excluindo deprecated e Phoenix Egg secundário |
| Feitiços | Spell Factory/Dark Spell Factory, excluindo eventos de calendário |

Versão `18.400.9` contém no catálogo seis heróis de vila principal:

- Rei Bárbaro;
- Rainha Arqueira;
- Grande Guardião;
- Campeã Real;
- Príncipe Servo;
- Duque Dracônico.

O sexto herói e unidades recentes demonstram por que modelo, labels e catálogo precisam declarar a versão do jogo.

## Como usar no reconhecimento

`IconExportName` é identificador de domínio, não label visual suficiente. Contrato recomendado:

```text
unitId: characters:TID_BARBARIAN
displayName: Bárbaro
visualExport: sc/ui.sc#icon_unit_barbarian
gameVersion: 18.400.9
locale: pt
referenceSet: screenshots/barbarian/v1
```

Manter separados:

- identidade do jogo;
- nome localizado;
- referência extraída;
- screenshots rotulados;
- classe usada pelo detector;
- versão mínima e máxima compatível.

Para seleção de tropas, detector deve reconhecer o card inteiro em screenshot real. Ícone do atlas serve para inicializar busca e revisar rótulos, não para provar que template funciona em todo aparelho.
