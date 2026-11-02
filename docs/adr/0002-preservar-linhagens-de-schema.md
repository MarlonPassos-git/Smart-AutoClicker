# ADR 0002: Preservar as linhagens de dados do fork e do upstream

Status: implementado na branch de sincronização; integração no `master` pendente.

## Contexto

A sincronização com a release upstream `4.1.2`, commit
`699bff818519bc418addb7f0bdad5fbfb6c3f720`, parte do fork em
`a8bcc0b271efcfcd372c9ae2c78bbf3740b6fb8c`. A release publicada do fork
`4.0.0-beta05` aponta para `5bc324deaa4baeac60bb9c21449205287123617f`.

Os números dos schemas já publicados têm significados diferentes:

| Banco | Fork | Upstream |
| --- | --- | --- |
| Smart 24 | Zoom | Gestos com múltiplos dedos e conversão de cliques e swipes |
| Smart 25 | Alternativas de texto | Coordenadas de condições como operandos de contadores |
| Smart 26 | Clique poligonal | Não publicado nesta release |
| Smart 27 | Referências de imagem normalizadas | Não publicado nesta release |
| Dumb 3 | Zoom | Gestos com múltiplos dedos e conversão de swipes |

Substituir os schemas antigos ou escolher uma migração somente pelo número da
versão comprometeria instalações ou backups existentes.

## Decisão

- Preservar os schemas históricos do fork e a migração smart `26 → 27`.
- Usar smart `28` e dumb `4` como destinos combinados.
- Registrar caminhos diretos smart `23…27 → 28` e dumb `2…3 → 4`.
  A estrutura existente determina se a conversão de gestos já ocorreu.
- Reutilizar os conversores de gestos upstream quando suas tabelas ainda não
  existem. Preservar a duração dos cliques poligonais antes da remoção das
  colunas legadas de clique e restaurá-la depois.
- Acrescentar somente os campos ausentes de cada linhagem. Criar referências
  normalizadas somente quando essa tabela ainda não existe.
- Preservar zoom e clique poligonal como tipos próprios. Converter swipes
  legados para o gesto equivalente do upstream.
- Identificar também a estrutura dos backups: condições e ações podem estar
  planas ou acompanhadas de suas relações. O leitor dumb aceita os campos
  `dumbScenarioId` e `dumb_scenario_id` nos formatos legados.

## Consequências

As migrações e importações devem preservar ordem, duplicatas, retângulos por
referência, alternativas de texto e parâmetros das ações. O espelhamento da
primeira referência e a limpeza de arquivos compartilhados continuam seguindo
o [ADR 0001](0001-normalize-image-references.md).

As regressões exercitam os schemas históricos do fork e os formatos upstream
obtidos pelos conversores originais. A validação termina no schema combinado,
sem substituir arquivos de schema publicados para fazer os testes passarem.

Atualizações posteriores devem ampliar essa sequência e sua cobertura quando
qualquer linhagem publicar novos schemas ou alterar os formatos de backup.
