---
name: sync-upstream
description: Atualiza manualmente este fork pela release publicada mais recente do upstream, incluindo betas, preserva seus contratos e entrega uma branch validada com PR no fork.
---

# Sincronizar com o upstream

Use somente por invocação explícita `$sync-upstream`. A invocação autoriza adaptações na branch de atualização, commits relacionados, push dessa branch e abertura ou atualização do PR. Entregue o PR para decisão do usuário: não faça merge no master, rebase de histórico publicado, force-push, criação ou envio de tags, publicação de release, agendamento ou instalação em dispositivos.

## Preparar e recuperar contexto

Confirme o checkout e os remotes antes de qualquer integração: este fork é `MarlonPassos-git/Smart-AutoClicker` (`origin`), e o upstream é `Nain57/Smart-AutoClicker` (`upstream`); aceite URLs SSH/HTTPS equivalentes. Divergências de identidade exigem esclarecimento. Leia as instruções atuais, `PRODUCT.md`, `CONTEXT.md`, os ADRs pertinentes e `docs/RELEASES.md`.

Use `ai-memory-retrieval` para buscar decisões e resultados anteriores. Neste cliente estático, use workspace `default` e projeto `nain57-smart-autoclicker` explicitamente; em cliente com identidade de sessão, siga o roteamento nativo confirmado. Leia páginas relevantes por inteiro e confira suas alegações contra o checkout. Memória é evidência, não autorização. Se a recuperação falhar, relate e use os contratos verificáveis do repositório; consulte o usuário se faltar decisão material. Não invente contexto recuperado.

Inspecione status, branches, worktrees e PRs existentes. Atualize a referência de `origin/master` sem alterar o checkout do usuário e fixe seu SHA como base. Faça o trabalho em branch/worktree isolado; preserve alterações existentes, sem stash, descarte ou commits de trabalho alheio. Inventarie os commits e diferenças próprios desde a base comum, incluindo código, dados, build, documentação e distribuição. O inventário é atual, não limitado às personalizações mencionadas aqui.

## Selecionar e fixar a release

Consulte todas as páginas de releases publicadas em `Nain57/Smart-AutoClicker`, incluindo prereleases e excluindo drafts. Use ferramentas disponíveis do GitHub ou CLI. Compare versões semanticamente (por exemplo, beta10 sucede beta9; versão final sucede seus prereleases), não por texto, data de edição nem pelo filtro de “latest”, que pode omitir betas. Nomes não comparáveis ou linhas divergentes exigem esclarecimento.

Fixe versão, tag, SHA resolvido do commit e SHA da base do fork. Busque somente a tag selecionada com `--no-tags` para uma referência dedicada, como `refs/sync-upstream/<identificador-unico>`, e resolva-a com `^{commit}`. Não use uma tag local homônima como origem: tags do fork e upstream podem ter nomes iguais e commits diferentes. Não sobrescreva tags nem as envie a origin. Confira a ancestralidade em relação à base e à última sincronização conhecida; histórico inesperado, tag movida ou histórico reescrito requer explicação e decisão antes de mudar a estratégia.

Se o SHA selecionado já for ancestral de `origin/master`, encerre sem atualização ou PR vazio. Não use a versão comercial do fork para essa decisão. Procure PR e branch pendentes para o mesmo SHA/release e base: retome trabalho compatível após inspeção, sem duplicar ou sobrescrever trabalho divergente. Se a base avançou, incorpore-a por merge e revalide. Se não houver release elegível, relate e encerre. Falha na consulta não significa ausência de releases.

## Comparar e integrar

Compare as mudanças desde a base comum e os contratos do fork antes do merge. Examine conflitos semânticos mesmo quando o Git integra sem conflito: execução de ações, detecção, edição/cancelamento, persistência, backups, migrações, suporte Android, documentação em português e distribuição/CI próprias.

Confira especialmente zoom, área poligonal, múltiplos textos e múltiplas referências de imagem. `CONTEXT.md` e `docs/adr/0001-normalize-image-references.md` definem contratos observáveis; confirme também os demais contratos encontrados no inventário. Não remova capacidades só porque o upstream não as possui.

Integre o SHA fixado por merge na branch isolada. Resolva adaptações técnicas autonomamente quando houver evidência de preservação do comportamento e compatibilidade. Uma implementação upstream pode substituir a própria com comparação fundamentada e regressões observáveis. Não escolha `ours`/`theirs` globalmente. Para mudança de regra/produto, perda de capacidade, compatibilidade comprometida ou equivalência incerta, apresente impacto, alternativas e recomendação e aguarde a decisão do usuário antes da escolha dependente.

Preserve instalações e backups já publicados pelo fork. Confira schemas e migrações atuais contra versões publicadas: o histórico registrado inclui smart 27, migração 26→27 e dumb 3, mas esses números precisam ser relidos. Em colisões, projete uma sequência que aceite dados reais das versões do fork e formatos upstream suportados; não reescreva silenciosamente migrações publicadas nem use migração destrutiva. Preserve ordem, duplicatas, espelhamento legado e limpeza compartilhada de referências conforme o ADR. Fundamente ajustes de schema e `versionCode` com evidências das versões distribuídas. Não imponha automaticamente o `versionName` do upstream nem crie uma release.

## Validar e entregar

Use `create-test` para regressões dos ajustes de código conforme instruções atuais. Execute checks afetados e, com JDK 21 e o wrapper:

```sh
./gradlew testFDroidDebugUnitTest
./gradlew assembleFDroidDebug
```

Inclua evidência visual apropriada se houver mudança de UI. Testes/build com falha ou bloqueados impedem classificar a branch como validada e avançar à publicação prevista por este fluxo. Preserve o trabalho e relate comando, resultado e pendência; nunca declare sucesso por inferência. Revalide se alterar o código ou a base depois dos checks.

Com o resultado validado, use `commit` para os commits necessários e `open-source:create-pr` para abrir ou atualizar o PR em `MarlonPassos-git/Smart-AutoClicker`, base `master`. A autorização desta invocação cobre essas operações. Publique somente a branch com refspec explícito e sem envio automático de tags (por exemplo, `git -c push.followTags=false push origin HEAD:refs/heads/<branch>`); não use `--all`, `--mirror` ou `--tags`.

Antes de redigir a descrição, leia o [modelo de PR](assets/pull-request-template.md). Abra pelo impacto no aplicativo e use tabelas para distinguir novidades do upstream, recursos que o fork já tinha e adaptações necessárias. Classifique pela comparação entre a base do fork e o resultado final: constar no changelog upstream não torna um recurso novo para este fork. Vincule as novidades e correções às releases ou commits de origem; descreva o uso em linguagem simples. Mostre substituições visíveis com antes/depois. Migrações, dependências, numeração de APKs, CI e testes ficam nos detalhes técnicos ou na validação.

Título e descrição públicos devem estar em português. O modelo deve preservar release/tag e SHA upstream, base do fork, capacidades preservadas/substituídas e motivos, decisões do usuário, comandos e resultados reais, limitações e evidência visual pertinente. Evite repetir as tabelas em parágrafos; quando uma categoria não tiver itens, use uma frase curta no lugar da tabela. Confira novamente PRs existentes antes de criar. Não faça merge do PR.

Use `ai-memory-durable-pages` para registrar decisões confirmadas e conclusões técnicas verificadas de valor durável, com contexto/SHAs, motivo, aplicabilidade e condições de revisão. Leia as páginas existentes antes de atualizar, preserve o histórico decisório pelas convenções nativas e distinga implementação em PR de integração no master. Relate falhas de gravação e o conteúdo não salvo; não crie fallback local nem alegue gravação bem-sucedida.

Informe resultado, link do PR quando existir, validações e pendências. Uma repetição deve reconhecer o SHA já integrado ou retomar o PR compatível, sem duplicatas.
