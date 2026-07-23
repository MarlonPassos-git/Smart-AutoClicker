# Processo completo de extração das imagens e dos ícones

Este documento registra o procedimento usado para extrair o Clash of Clans
instalado, converter tabelas e atlas e separar todos os ícones de tropas,
heróis, pets e feitiços referenciados pela versão `18.400.9`.

O resultado validado foi:

```text
100 exports de tropas e variantes
8 exports de heróis
16 exports de pets e variantes
26 exports de feitiços e variantes
148 PNGs únicos
0 exports sem imagem
```

Soma por tabela é 150 porque dois exports são compartilhados por mais de uma
tabela. `icon_unit_pet_phoenix`, por exemplo, aparece como pet e como referência
de feitiços internos.

## Escopo real

Processo produz:

- todos os arquivos que já eram PNG dentro dos APKs;
- sete atlas completos embutidos em `sc/ui.sc`;
- texturas `.sctx` compatíveis com ferramentas usadas;
- um inventário estrutural dos exports, `MovieClips`, shapes e UVs do `ui.sc`;
- um PNG transparente para cada `IconExportName` encontrado em
  `characters.csv`, `heroes.csv`, `pets.csv` e `spells.csv`;
- cópias agrupadas por categoria;
- folhas visuais e manifesto JSON.

Processo não transforma automaticamente todo frame de animação, modelo 3D ou
composição dinâmica da interface em imagem individual. Cards mostrados pelo
jogo combinam ícone, fundo, nível, quantidade, badge e outros elementos.

## Visão geral

```text
Celular Android
    ↓ ADB
seis APKs instalados
    ↓ unzip
asset pack com logic/, localization/, sc/ e ui/
    ├─ sc-compression → CSVs legíveis
    └─ SupercellFlash
         ├─ SCTex → sete atlas PNG na ordem correta
         └─ helper C++ → grafo e coordenadas em ui-objects.tsv
                              ↓
             extract_coc_ui_icons.py
                 ├─ cruza IconExportName com exports
                 ├─ percorre MovieClips e shapes
                 ├─ recorta malha UV
                 ├─ corrige rotação/espelhamento
                 └─ gera 148 PNGs + manifesto + folhas
```

## Regras de armazenamento

APKs e assets são proprietários. Todo material bruto ou convertido permanece
em:

```text
.local-research/clash-of-clans/18.400.9/
```

Diretório está ignorado por `/.gitignore`. Não enviar APKs, CSVs brutos,
texturas ou PNGs extraídos para Git, releases ou APK do fork.

Somente documentação e scripts de pesquisa ficam versionados.

## Ferramentas usadas

| Ferramenta | Uso |
| --- | --- |
| ADB | descobrir pacote e copiar APKs instalados |
| `unzip`, `sha256sum` | validar e abrir splits |
| Node.js + [`sc-compression`](https://github.com/jeanbmar/sc-compression) `2.1.0` | descompactar CSVs com assinatura `Sig:` |
| [`mb-sc-tools`](https://pypi.org/project/mb-sc-tools/) `0.1.3` | exploração inicial de `.sc` e `.sctx` |
| `astcenc` | decodificar texturas ASTC usadas por `mb-sc-tools` |
| [`SupercellFlash`](https://github.com/sc-workshop/SupercellFlash) | ler container SC2 e preservar índices de textura |
| FlatBuffers `25.12.19` | dependência de build do `SupercellFlash` |
| Python + Pillow `12.3.0` | recortar, mascarar, orientar e montar folhas |
| `jq` | inspecionar manifesto final |

O `SupercellFlash` usado estava no commit
`41e894d5a20cc17e47fe32db3106c4c1bec60a3e`.

## 1. Preparar diretórios

Execute a partir da raiz do Smart AutoClicker:

```bash
export COC_VERSION="18.400.9"
export COC_RESEARCH_ROOT="$PWD/.local-research/clash-of-clans/$COC_VERSION"
export COC_APK_ROOT="$COC_RESEARCH_ROOT/apks"
export COC_EXTRACTED_ROOT="$COC_RESEARCH_ROOT/extracted"
export COC_DECODED_ROOT="$COC_RESEARCH_ROOT/decoded"

mkdir -p "$COC_APK_ROOT" "$COC_EXTRACTED_ROOT" "$COC_DECODED_ROOT"
```

Versão é parte do caminho de propósito. Uma atualização do jogo deve gerar
outro diretório, hashes e catálogo.

## 2. Descobrir pacote e splits no celular

Conecte aparelho autorizado com depuração USB:

```bash
adb devices -l
adb shell dumpsys package com.supercell.clashofclans
adb shell pm path com.supercell.clashofclans
```

Captura usada encontrou:

```text
package: com.supercell.clashofclans
versionName: 18.400.9
versionCode: 180400010
launchActivity: com.supercell.titan.GameApp
device: Samsung SM-S916B
ABI: arm64-v8a
```

`pm path` retornou seis arquivos:

```text
base.apk
split_config.arm64_v8a.apk
split_config.en.apk
split_config.pt.apk
split_config.xxhdpi.apk
split_install_time_asset_pack.apk
```

Copie cada caminho exato retornado pelo comando:

```bash
adb pull "/caminho/retornado/base.apk" "$COC_APK_ROOT/base.apk"
adb pull "/caminho/retornado/split_config.arm64_v8a.apk" \
  "$COC_APK_ROOT/split_config.arm64_v8a.apk"
adb pull "/caminho/retornado/split_config.en.apk" \
  "$COC_APK_ROOT/split_config.en.apk"
adb pull "/caminho/retornado/split_config.pt.apk" \
  "$COC_APK_ROOT/split_config.pt.apk"
adb pull "/caminho/retornado/split_config.xxhdpi.apk" \
  "$COC_APK_ROOT/split_config.xxhdpi.apk"
adb pull "/caminho/retornado/split_install_time_asset_pack.apk" \
  "$COC_APK_ROOT/split_install_time_asset_pack.apk"
```

Não tentar acessar armazenamento privado interno do jogo. Splits retornados por
`pm path` foram suficientes para este inventário.

## 3. Validar APKs antes de extrair

```bash
for apk_path in "$COC_APK_ROOT"/*.apk; do
  unzip -tqq "$apk_path"
  sha256sum "$apk_path"
done
```

Todos os seis ZIPs precisam retornar sucesso. Hashes observados estão em
[package-manifest.json](package-manifest.json).

## 4. Extrair cada split separadamente

Manter diretório próprio evita colisões silenciosas:

```bash
for apk_path in "$COC_APK_ROOT"/*.apk; do
  apk_name="$(basename "$apk_path" .apk)"
  mkdir -p "$COC_EXTRACTED_ROOT/$apk_name"
  unzip -q "$apk_path" -d "$COC_EXTRACTED_ROOT/$apk_name"
done
```

Asset pack relevante fica em:

```bash
export COC_ASSET_ROOT="$COC_EXTRACTED_ROOT/split_install_time_asset_pack/assets"
```

Estrutura principal:

```text
assets/
├── logic/
├── localization/
├── sc/
├── sc3d/
├── ui/
└── image/
```

Versão analisada possuía 9.324 arquivos no asset pack, incluindo 599 `.sctx`,
315 `.sc`, 139 `.csv`, 574 `.glb` e 60 `.png`.

PNGs comuns já podem ser inventariados diretamente:

```bash
find "$COC_ASSET_ROOT" -type f -name '*.png' -print
```

## 5. Descompactar tabelas `Sig:`

Arquivos de `logic/` e `localization/` têm extensão `.csv`, mas conteúdo começa
com `Sig:`. Não são CSVs legíveis até passar pelo descompressor da Supercell.

Prepare ferramenta temporária:

```bash
mkdir -p /tmp/coc-sc-compression
cd /tmp/coc-sc-compression
npm init -y
npm install sc-compression@2.1.0
```

Crie `/tmp/coc-sc-compression/decompress-tables.mjs`:

```javascript
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { decompress } from 'sc-compression'

const [sourceDirectory, outputDirectory] = process.argv.slice(2)

if (!sourceDirectory || !outputDirectory) {
  throw new Error(
    `Invalid directories: ${sourceDirectory}, ${outputDirectory}; expected SOURCE OUTPUT`
  )
}

await mkdir(outputDirectory, { recursive: true })

for (const filename of await readdir(sourceDirectory)) {
  if (!filename.endsWith('.csv')) continue
  const compressed = await readFile(join(sourceDirectory, filename))
  const decoded = await decompress(compressed)
  await writeFile(join(outputDirectory, filename), decoded)
}
```

Execute:

```bash
node /tmp/coc-sc-compression/decompress-tables.mjs \
  "$COC_ASSET_ROOT/logic" \
  "$COC_DECODED_ROOT/tables/logic"

node /tmp/coc-sc-compression/decompress-tables.mjs \
  "$COC_ASSET_ROOT/localization" \
  "$COC_DECODED_ROOT/tables/localization"
```

Resultado usado pelo extrator:

```text
decoded/tables/
├── logic/
│   ├── characters.csv
│   ├── heroes.csv
│   ├── pets.csv
│   ├── spells.csv
│   └── ...
└── localization/
    ├── texts.csv
    ├── texts_patch.csv
    └── pt.csv
```

Foram obtidos 91 CSVs de lógica e três de localização.

Campos decisivos:

```text
Name
TID
IconSWF
IconExportName
ProductionBuilding
VillageType
EnabledByCalendar
EnabledBySuperLicence
DefensiveTroop
IsSecondaryTroop
Deprecated
```

`IconSWF` dos 148 exports únicos apontava para `sc/ui.sc`.

## 6. Exploração inicial de `.sc` e `.sctx`

Ambiente Python temporário usado:

```bash
python3 -m venv /tmp/coc-asset-tools
/tmp/coc-asset-tools/bin/pip install \
  "mb-sc-tools==0.1.3" \
  "flatbuffers==25.12.19" \
  "lz4==4.4.5" \
  "Pillow==12.3.0" \
  "zstandard==0.25.0"
```

Exemplo de decodificação:

```bash
/tmp/coc-asset-tools/bin/mb-sc-tools decode \
  "$COC_ASSET_ROOT/sc/characters_0.sctx" \
  --astcenc "/caminho/para/astcenc-avx2" \
  --output-dir "$COC_DECODED_ROOT/character_textures/characters_0"
```

Essa etapa confirmou formatos e permitiu converter várias texturas. Não foi
usada como fonte final dos índices do `ui.sc`: ordem de atlas gerada por essa
ferramenta não correspondia aos `texture_index` lidos do SC2 atual.

## 7. Compilar `SupercellFlash`

### 7.1 FlatBuffers

```bash
git clone --depth 1 --branch v25.12.19 \
  https://github.com/google/flatbuffers.git \
  /tmp/flatbuffers

cmake -S /tmp/flatbuffers -B /tmp/flatbuffers-build \
  -DCMAKE_BUILD_TYPE=Release \
  -DFLATBUFFERS_BUILD_TESTS=OFF \
  -DCMAKE_INSTALL_PREFIX=/tmp/flatbuffers-install

cmake --build /tmp/flatbuffers-build --parallel
cmake --install /tmp/flatbuffers-build
```

### 7.2 SupercellFlash e helper estrutural

```bash
git clone https://github.com/sc-workshop/SupercellFlash.git \
  /tmp/supercell-flash

git -C /tmp/supercell-flash checkout \
  41e894d5a20cc17e47fe32db3106c4c1bec60a3e

cp scripts/research/supercell_flash_ui_dump.cpp \
  /tmp/supercell-flash/tools/test-tool/source/main.cpp

cmake -S /tmp/supercell-flash -B /tmp/supercell-flash-build \
  -DCMAKE_BUILD_TYPE=Release \
  -DSC_FLASH_BUILD_TOOLS=ON \
  -Dflatbuffers_DIR=/tmp/flatbuffers-install/lib/cmake/flatbuffers \
  -DFLATBUFFERS_FLATC_EXECUTABLE=/tmp/flatbuffers-install/bin/flatc

cmake --build /tmp/supercell-flash-build --parallel
```

Binários necessários:

```text
/tmp/supercell-flash-build/tools/test-tool/supercell-flash-test-tool
/tmp/supercell-flash-build/tools/texture-tool/SCTex
```

### Ajustes exigidos pelo compilador usado

Dependências buscadas pelo CMake precisaram de três correções locais. Essas
correções são de compatibilidade de build, não mudanças no formato:

1. adicionar `#include <cstdint>` em
   `_deps/workshopcore-src/workshop-core/source/core/memory/memory.h`;
2. remover `#pragma region` e `#pragma endregion` de dentro do enum em
   `_deps/supercell-texture-src/supercell-texture/source/texture/backend/Supercell/ScPixel.hpp`;
3. trocar `std::execution::par_unseq` pelas versões sequenciais de
   `find`, `find_if`, `sort` e `stable_sort` em
   `_deps/workshopcore-src/workshop-core/source/core/algorithm/`.

Terceiro ajuste evitou dependência de link ausente com oneTBB. Em ambiente com
oneTBB configurado, pode não ser necessário. Aplicar somente quando build
apresentar esses erros.

## 8. Decodificar sete atlas na ordem correta

```bash
export COC_UI_SC="$COC_ASSET_ROOT/sc/ui.sc"

/tmp/supercell-flash-build/tools/texture-tool/SCTex \
  decode "$COC_UI_SC" -o ui-current
```

Ferramenta cria `assets/sc/ui-current/`. Copie renomeando para formato esperado
pelo extrator:

```bash
mkdir -p "$COC_DECODED_ROOT/ui-render"

for atlas_index in 0 1 2 3 4 5 6; do
  source_name="$COC_ASSET_ROOT/sc/ui-current/ui-current_${atlas_index}.png"
  target_name="$(printf 'ui_%03d.png' "$atlas_index")"
  cp "$source_name" "$COC_DECODED_ROOT/ui-render/$target_name"
done
```

Ordem validada:

| Índice | Dimensão |
| ---: | ---: |
| 0 | 4096 × 4096 |
| 1 | 4096 × 4096 |
| 2 | 4096 × 4096 |
| 3 | 4096 × 4096 |
| 4 | 4096 × 4096 |
| 5 | 3050 × 3514 |
| 6 | 912 × 1024 |

### Erro importante: atlas invertidos

Primeira conversão gerou ordem `912 × 1024`, `3050 × 3514` e depois cinco
atlas `4096 × 4096`. Usar essa ordem com os `texture_index` do `SupercellFlash`
produzia recortes de regiões completamente diferentes.

Sintoma: `icon_unit_barbarian` mostrava pedaços aleatórios da interface.

Correção: usar exclusivamente `decoded/ui-render/` com ordem da tabela acima.

## 9. Gerar dump estrutural do `ui.sc`

Helper versionado em
`scripts/research/supercell_flash_ui_dump.cpp` escreve TSV na saída padrão:

```bash
mkdir -p "$COC_DECODED_ROOT/ui"

/tmp/supercell-flash-build/tools/test-tool/supercell-flash-test-tool \
  "$COC_UI_SC" \
  > "$COC_DECODED_ROOT/ui/ui-objects.tsv"
```

Tipos de registro:

| Registro | Conteúdo |
| --- | --- |
| `COUNTS` | totais de exports, shapes, MovieClips e texturas |
| `EXPORT` | ID, nome exportado e hash |
| `VERTEX` | shape, comando, textura, posição XY e UV |
| `MOVIE` | ID, banco de matrizes e frame rate |
| `CHILD` | relação entre MovieClip e objeto filho |
| `FRAME` | número de elementos e label |
| `ELEMENT` | instância, matriz e transformação de cor |
| `MATRIX` | transformação geométrica |

Resultado desta versão:

```text
COUNTS  3024 exports  4053 shapes  6322 MovieClips  7 texturas
1.250.506 linhas
43.764.770 bytes
```

Primeira linha exata:

```text
COUNTS	3024	4053	6322	7
```

## 10. Separar todos os ícones

Execute script versionado:

```bash
cd /home/marlonpassos/OpenSource/Smart-AutoClicker

/tmp/coc-asset-tools/bin/python \
  scripts/research/extract_coc_ui_icons.py \
  --research-root "$COC_RESEARCH_ROOT"
```

Também é possível apontar outro conjunto de atlas:

```bash
/tmp/coc-asset-tools/bin/python \
  scripts/research/extract_coc_ui_icons.py \
  --research-root "$COC_RESEARCH_ROOT" \
  --atlas-root "$COC_DECODED_ROOT/ui-render"
```

Saída esperada:

```json
{"uniqueIconExports": 148, "extracted": 148, "missing": 0}
```

## 11. Como extrator resolve cada imagem

### 11.1 Inventário semântico

Script lê:

```text
decoded/tables/logic/characters.csv
decoded/tables/logic/heroes.csv
decoded/tables/logic/pets.csv
decoded/tables/logic/spells.csv
decoded/tables/localization/pt.csv
```

Cada linha com `IconExportName` vira alias. Linhas diferentes podem usar mesmo
export; nenhuma informação é descartada. Manifesto guarda:

- tabela de origem;
- nome interno;
- TID;
- nome em português;
- categoria;
- village type;
- prédio de produção;
- flags de evento e deprecated.

### 11.2 Navegação pelo grafo

Para cada nome, script:

1. encontra ID em `EXPORT`;
2. abre objeto correspondente;
3. percorre filhos do primeiro frame se objeto for `MovieClip`;
4. continua até chegar aos shapes;
5. reúne comandos e vértices desses shapes;
6. seleciona maior região válida quando há candidatos.

Primeiro frame foi usado porque exports de ícones representam apresentação
estática inicial. Animações completas exigiriam renderização de timeline.

### 11.3 Conversão de UV para pixels

Cada vértice contém coordenadas normalizadas `u` e `v`. Conversão é:

```text
pixelX = u × larguraDoAtlas
pixelY = v × alturaDoAtlas
```

Limites usam `floor` no topo/esquerda e `ceil` em baixo/direita para não cortar
pixel de borda.

### 11.4 Máscara da malha

Sprites são empacotados como malhas triangulares. Recortar apenas retângulo
mínimo trouxe pixels de imagens vizinhas em supertropas, variantes de evento e
máquinas de cerco.

Correção aplicada:

1. construir máscara transparente no tamanho do recorte;
2. interpretar cada trio consecutivo de vértices como triangle strip;
3. preencher união dos triângulos;
4. multiplicar máscara pelo canal alpha original;
5. remover pixels do atlas fora da malha real.

### 11.5 Rotação e espelhamento

Atlas gira e espelha sprites para economizar espaço. Script normaliza XY e UV e
compara oito transformações:

```text
identity
flip-left-right
flip-top-bottom
rotate-180
rotate-90
rotate-270
transpose
transverse
```

Transformação com menor erro entre posição geométrica e UV é aplicada ao PNG.
Distribuição observada:

| Transformação | Ícones |
| --- | ---: |
| identity | 100 |
| flip-left-right | 14 |
| flip-top-bottom | 4 |
| rotate-180 | 1 |
| rotate-90 | 20 |
| rotate-270 | 1 |
| transpose | 5 |
| transverse | 3 |

Sem etapa, várias tropas aparecem de lado, invertidas ou espelhadas.

## 12. Organização gerada

```text
icons/
├── by-export/              # fonte deduplicada: 148 PNGs
├── troops/
│   ├── main-village/
│   ├── super-troops/
│   ├── siege-machines/
│   ├── builder-base/
│   ├── event-or-seasonal/
│   ├── secondary-or-summoned/
│   └── other-variants/
├── heroes/
│   ├── main-village/
│   └── builder-base/
├── pets/
│   ├── pets/
│   └── secondary-or-summoned/
├── spells/
│   ├── elixir-spells/
│   ├── dark-spells/
│   ├── event-or-seasonal/
│   └── other-variants/
├── troops.png
├── heroes.png
├── pets.png
├── spells.png
├── all-icons.png
└── manifest.json
```

Folhas renderizadas:

### Tropas e variantes

![100 ícones de tropas e variantes](../../../.local-research/clash-of-clans/18.400.9/icons/troops.png)

### Heróis

![8 ícones de heróis](../../../.local-research/clash-of-clans/18.400.9/icons/heroes.png)

### Pets e variantes

![16 ícones de pets e variantes](../../../.local-research/clash-of-clans/18.400.9/icons/pets.png)

### Feitiços e variantes

![26 ícones de feitiços e variantes](../../../.local-research/clash-of-clans/18.400.9/icons/spells.png)

Imagens renderizam somente em checkout que possui `.local-research/`.

## 13. Validação final

### Manifesto

```bash
jq '.summary' "$COC_RESEARCH_ROOT/icons/manifest.json"
```

Esperado:

```json
{
  "uniqueIconExports": 148,
  "extracted": 148,
  "missing": 0
}
```

### Quantidade de PNGs deduplicados

```bash
find "$COC_RESEARCH_ROOT/icons/by-export" \
  -maxdepth 1 -type f -name '*.png' | wc -l
```

Esperado: `148`.

### Transparência não vazia

```bash
/tmp/coc-asset-tools/bin/python -c "
from pathlib import Path
from PIL import Image

root = Path('$COC_RESEARCH_ROOT/icons/by-export')
paths = list(root.glob('*.png'))
empty = [
    path.name
    for path in paths
    if Image.open(path).convert('RGBA').getchannel('A').getbbox() is None
]
print({'pngFiles': len(paths), 'emptyAlpha': len(empty), 'files': empty})
"
```

Esperado:

```text
{'pngFiles': 148, 'emptyAlpha': 0, 'files': []}
```

### Verificação visual

Abrir quatro folhas e conferir:

- rostos e frascos estão na orientação correta;
- não existem pixels de sprites vizinhos;
- transparência permanece fora do personagem;
- labels correspondem ao export;
- imagens sazonais e supertropas também aparecem.

Exemplos usados como sentinela:

```text
icon_unit_barbarian
icon_unit_archer
icon_unit_elite_archer
icon_unit_siege_machine_catapult
icon_spell_lightning
icon_spell_healing
icon_unit_pet_phoenix
```

## 14. Problemas encontrados

| Problema | Causa | Solução |
| --- | --- | --- |
| Crop mostrava imagem errada | índices apontavam para atlas em ordem diferente | usar ordem gerada por `SCTex` |
| Ícone deitado ou espelhado | sprite empacotado com transformação | inferir uma de oito orientações |
| Fragmentos nas bordas | crop retangular incluía atlas fora da malha | aplicar máscara triangle strip |
| Mesmo PNG em categorias diferentes | export compartilhado por aliases | deduplicar em `by-export` e copiar por categoria |
| Soma 150, total 148 | dois exports aparecem em mais de uma tabela | contar chave `IconExportName` única |
| Alguns `.sctx` falharam | variação não suportada por `mb-sc-tools` | usar `SupercellFlash` no `ui.sc`; registrar falhas |
| Build pediu TBB | uso de `std::execution::par_unseq` | instalar oneTBB ou usar fallback sequencial |
| Dump ocupa dezenas de MB | um registro por vértice/objeto | manter somente em `.local-research` |

## 15. Atualizar para nova versão do jogo

Nunca reutilizar silenciosamente atlas ou manifesto antigo:

1. criar novo diretório com `versionName`;
2. repetir `pm path` e `adb pull`;
3. validar ZIPs e registrar SHA-256;
4. extrair asset pack;
5. descompactar tabelas;
6. decodificar novo `ui.sc`;
7. gerar novo `ui-objects.tsv`;
8. executar extrator;
9. comparar manifests por `IconExportName`;
10. revisar adicionados, removidos e exports com UV alterado;
11. regenerar screenshots de treino quando interface tiver mudado.

Ícone extraído serve como ground truth semântico e referência de catálogo.
Detector final deve continuar validado com screenshots reais em diferentes
resoluções, escalas, aparelhos e estados do jogo.
