# Extração local do APK de Clash of Clans

Status: concluída para o pacote instalado

Data: 23 de julho de 2026

Versão: `18.400.9` (`180400010`)

Pacote: `com.supercell.clashofclans`

## Resultado

Clash of Clans foi extraído do Samsung SM-S916B conectado por ADB. A instalação contém seis APKs:

- APK base;
- código nativo ARM64;
- recursos de inglês;
- recursos de português;
- recursos XXHDPI;
- asset pack instalado junto com o aplicativo.

Todos os APKs passaram em `unzip -tqq`. Tamanhos, número de entradas e hashes SHA-256 estão em [package-manifest.json](package-manifest.json).

Os binários e assets proprietários ficam apenas em:

```text
.local-research/clash-of-clans/18.400.9/
├── apks/       # seis APKs originais
├── extracted/  # conteúdo ZIP de cada APK
├── decoded/    # CSVs e texturas convertidos
├── catalog/    # catálogo derivado de tropas, heróis, pets e feitiços
└── icons/      # PNGs separados por export e categoria
```

`/.local-research/` está no `.gitignore`. APKs, imagens, áudio, fontes e tabelas brutas não devem entrar no Git nem em releases.

## Conteúdo encontrado

O asset pack tem 9.324 arquivos sob `assets/`:

| Extensão | Quantidade | Uso observado |
| --- | ---: | --- |
| `.meta` | 5.865 | Metadados dos assets |
| `.ogg` | 1.489 | Efeitos e vozes |
| `.sctx` | 599 | Texturas compactadas da Supercell |
| `.glb` | 574 | Modelos 3D |
| `.sc` | 315 | Containers 2D, animações e UI |
| `.csv` | 139 | Lógica, configuração e localização |
| `.toml` | 127 | Layouts e componentes de UI |
| `.shader` | 89 | Shaders |
| `.png` | 60 | Imagens comuns |
| outros | 67 | JSON, fontes, vídeo, música e formatos auxiliares |

Diretórios mais relevantes:

```text
assets/
├── logic/          # tropas, heróis, pets, feitiços, construções e upgrades
├── localization/   # textos, inclusive pt.csv
├── sc/             # personagens, construções e ui.sc
├── sc3d/           # modelos e ambientes 3D
├── ui/             # layouts TOML e recursos específicos de telas
└── image/          # PNGs e texturas auxiliares
```

## Tabelas de tropas

Arquivos em `assets/logic/` começam com `Sig:` e não são CSVs legíveis diretamente. O pacote aberto [sc-compression](https://github.com/jeanbmar/sc-compression) descompactou essas tabelas sem modificar o APK.

Foram convertidos 91 CSVs de lógica e três de localização:

```text
.local-research/clash-of-clans/18.400.9/decoded/tables/
├── logic/
│   ├── characters.csv
│   ├── heroes.csv
│   ├── pets.csv
│   ├── spells.csv
│   ├── buildings.csv
│   └── ...
└── localization/
    ├── texts.csv
    ├── texts_patch.csv
    └── pt.csv
```

`characters.csv` fornece, por nível:

- nome interno e `GlobalID`;
- TID usado na localização;
- espaço no exército;
- nível de quartel e laboratório;
- vida, DPS, alcance e velocidade;
- custos e tempos de upgrade;
- tipo de alvo;
- efeitos e projéteis;
- arquivo visual;
- nome exportado do ícone.

Exemplo confirmado:

```text
Name: Barbarian
TID: TID_BARBARIAN
Nome pt: Bárbaro
IconSWF: sc/ui.sc
IconExportName: icon_unit_barbarian
BigPictureSWF: sc/info_barbarian.sc
```

Catálogo derivado:

```text
.local-research/clash-of-clans/18.400.9/catalog/
├── troop-catalog.csv
└── troop-catalog.json
```

O catálogo tem 107 entradas classificadas:

| Categoria | Quantidade |
| --- | ---: |
| Tropas normais da vila principal | 31 |
| Supertropas | 17 |
| Máquinas de cerco | 9 |
| Tropas da Base do Construtor | 14 |
| Heróis da vila principal | 6 |
| Pets de herói | 12 |
| Feitiços produzidos | 18 |

Detalhes e critérios de classificação estão em [troop-catalog.md](troop-catalog.md).

## Imagens, atlas e ícones separados

Arquivos `.sctx` foram convertidos com `mb-sc-tools 0.1.3`, usando `astcenc`
para texturas ASTC. `sc/ui.sc` foi interpretado com
[SupercellFlash](https://github.com/sc-workshop/SupercellFlash), preservando
ordem de texturas, grafo de `MovieClip`, malha, UV e orientação de cada sprite.
Ferramentas são somente de pesquisa e foram instaladas em `/tmp`; não viraram
dependências do aplicativo.

`sc/ui.sc`, com 55,8 MB, gerou sete atlas:

```text
.local-research/clash-of-clans/18.400.9/decoded/ui-render/
├── ui_000.png  # 4096 × 4096
├── ui_001.png  # 4096 × 4096
├── ui_002.png  # 4096 × 4096
├── ui_003.png  # 4096 × 4096
├── ui_004.png  # 4096 × 4096
├── ui_005.png  # 3050 × 3514
└── ui_006.png  # 912 × 1024
```

Os atlas contêm:

- retratos de tropas, heróis, pets e feitiços;
- botões e estados de botões;
- recursos, badges e indicadores;
- painéis, fundos e popups;
- ícones de construções, upgrades e menus;
- imagens sazonais.

Pipeline `scripts/research/extract_coc_ui_icons.py` cruza quatro tabelas com
exports do `ui.sc`, percorre primeiro frame dos `MovieClips`, recorta malha no
atlas e corrige sprites empacotados com rotação ou espelhamento. Resultado:

```text
.local-research/clash-of-clans/18.400.9/icons/
├── by-export/  # 148 PNGs únicos
├── troops/     # categorias de tropas e variantes
├── heroes/     # vila principal e Base do Construtor
├── pets/       # pets e unidades secundárias
├── spells/     # fábricas, eventos e outras variantes
├── troops.png
├── heroes.png
├── pets.png
├── spells.png
├── all-icons.png
└── manifest.json
```

Foram extraídos 148 de 148 exports únicos, sem região ausente:

| Tabela de origem | Ícones únicos | Linhas/aliases |
| --- | ---: | ---: |
| `characters.csv` | 100 | 146 |
| `heroes.csv` | 8 | 8 |
| `pets.csv` | 16 | 18 |
| `spells.csv` | 26 | 38 |

### Tropas e todas as variantes

![100 ícones de tropas e variantes](../../../.local-research/clash-of-clans/18.400.9/icons/troops.png)

### Heróis

![8 ícones de heróis](../../../.local-research/clash-of-clans/18.400.9/icons/heroes.png)

### Pets e variantes

![16 ícones de pets e variantes](../../../.local-research/clash-of-clans/18.400.9/icons/pets.png)

### Feitiços e todas as variantes

![26 ícones de feitiços e variantes](../../../.local-research/clash-of-clans/18.400.9/icons/spells.png)

Detalhes, categorias e relação entre exports compartilhados estão em
[troop-catalog.md](troop-catalog.md).

Procedimento reproduzível, desde captura dos APKs até os 148 PNGs separados,
está em [icon-extraction-process.md](icon-extraction-process.md).

### Referências técnicas adicionais

![Texturas de personagens do Clash of Clans](../../../.local-research/clash-of-clans/18.400.9/decoded/character-textures-contact-sheet.png)

![Fundos e componentes dos cards de unidades](../../../.local-research/clash-of-clans/18.400.9/decoded/unit_icons/unit_icons_000.png)

As 12 texturas `characters_0.sctx` a `characters_11.sctx` também foram convertidas. Elas guardam partes e frames de animação, não um catálogo de retratos isolados.

## Menus e componentes

Layouts TOML em `assets/ui/` são legíveis. Exemplo: `ui/unit-icon/unit_icon.toml` define propriedades semânticas como:

```text
unit_icon
unit_level
unit_level_badge
unit_count
info_button
bg_default
bg_super_troop
bg_spell
bg_temp_troop
```

Isso ajuda a modelar estados do aplicativo, mas não fornece coordenadas de tela. Posição final depende do layout, viewport, escala e estado executado pelo jogo.

## Limitações

1. Recorte depende do dump estrutural local `decoded/ui/ui-objects.tsv` e dos
   atlas em `decoded/ui-render/`; script não decodifica `ui.sc` sozinho.
2. `@ultrapowa/sc-tools 2.0.4` foi avaliado para esse recorte, mas sua
   dependência nativa `gl` não compila com Node 24/Python 3.14 e projeto declara
   testes apenas contra assets de 2022.
3. Alguns `.sctx` de UI usam variação que `mb-sc-tools 0.1.3` ainda não
   interpreta. Isso não impediu conversão dos sete atlas embutidos de `ui.sc`.
4. Assets baixados depois da instalação podem ficar no armazenamento privado
   do aplicativo. Eles não fazem parte necessariamente dos seis APKs e não
   foram acessados.
5. Nenhuma proteção, sandbox Android ou assinatura foi contornada. Nenhum APK
   foi modificado ou recompilado.
6. Assets extraídos são referência de versão específica. Atualização do jogo
   exige novo hash, extração e comparação.

## Uso recomendado no fork

Assets do APK não devem virar templates finais automaticamente. Eles não reproduzem composição, escala, antialiasing, iluminação, skins, badges nem estado real da tela.

Fluxo seguro para visão:

1. usar catálogo para nomes, categorias e IDs;
2. capturar telas reais autorizadas no aparelho;
3. associar captura ao `IconExportName`;
4. recortar região visual estável, excluindo nível e contadores;
5. criar referências em múltiplas escalas ou treinar detector com screenshots;
6. validar por versão, idioma, viewport e aparelho;
7. manter assets proprietários fora do APK distribuído quando não houver autorização.

O valor maior desta extração é fornecer ground truth semântico e inventário de versão. Para automação portátil, dataset deve continuar baseado em frames reais de jogo.

## Repetição da captura

Descoberta da instalação:

```bash
adb shell pm path com.supercell.clashofclans
adb shell dumpsys package com.supercell.clashofclans
```

Cada caminho retornado por `pm path` deve ser copiado com `adb pull`. Depois:

1. validar todos os ZIPs;
2. calcular SHA-256;
3. extrair cada APK em diretório separado;
4. localizar `split_install_time_asset_pack/assets`;
5. converter tabelas `Sig:` somente para leitura;
6. converter `ui.sc` e `.sctx` compatíveis;
7. regenerar catálogo;
8. comparar hashes e nomes com versão anterior.

Não reutilizar este inventário silenciosamente após uma atualização.
