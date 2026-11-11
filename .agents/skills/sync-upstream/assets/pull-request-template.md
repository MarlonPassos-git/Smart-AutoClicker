# O que mudou e por quê

<!-- Resuma em uma frase a atualização e seu impacto no aplicativo. Preencha com fatos verificados; substitua as linhas de exemplo. -->

## Novidades recebidas do upstream

<!-- Inclua somente funcionalidades novas em relação à base do fork. Use a release ou o commit upstream como fonte e confira o comportamento no diff final. -->

| Recurso novo | O que passou a ser possível | Origem |
| --- | --- | --- |
| Nome do recurso | Exemplo concreto de uso | Link da release ou do commit |

## Recursos do fork preservados ou adaptados

<!-- Estes recursos já existiam. Ligue cada adaptação à mudança upstream que a tornou necessária; evite repetir a lista de novidades. -->

| Recurso já existente | Como ficou nesta atualização | Adaptação necessária |
| --- | --- | --- |
| Nome do recurso | Comportamento preservado ou alteração decidida | Ajuste realizado e motivo, ou "Sem adaptação específica" |

## Mudanças no uso

<!-- Registre substituições de controles ou fluxos, remoções e mudanças de comportamento. Identifique decisões do usuário quando houver. -->

| Antes | Depois | Efeito nos cenários existentes |
| --- | --- | --- |
| Controle ou comportamento anterior | Controle ou comportamento atual | Conversão, compatibilidade ou limitação |

## Correções relevantes

<!-- Liste os problemas corrigidos e o efeito para o usuário, com a origem quando conhecida. Não apresente correções como novas funcionalidades. -->

- Problema corrigido, efeito observado e link de origem.

<details>
<summary>Detalhes técnicos e rastreabilidade</summary>

- Release/tag upstream e link:
- SHA upstream:
- SHA da base do fork:
- Base comum ou última sincronização, quando pertinente:
- Estratégia de integração:
- Decisões do usuário e seus motivos:

| Área | Ajuste realizado | Motivo e compatibilidade |
| --- | --- | --- |
| Persistência e backups | Migração ou leitura adaptada | Formatos publicados aceitos e dados preservados |
| Distribuição e CI | Versão interna, dependências ou workflow alterado | Evidência que fundamenta o ajuste |

<!-- Mantenha apenas áreas afetadas. Informe a preservação de contratos próprios encontrados no inventário, inclusive os de documentação e distribuição. -->

</details>

## Como testar

| Comando ou verificação | Resultado real |
| --- | --- |
| Comando executado e ambiente necessário | Resultado, cobertura pertinente ou bloqueio |

<!-- Diferencie validações executadas de passos sugeridos ao revisor. Não marque como aprovado o que não foi executado. -->

Revisão manual sugerida:

1. Estado inicial, interação e resultado esperado.

Limitações e pendências:

- Verificação não executada ou restrição conhecida.

## Demonstração

<!-- Vincule cada imagem ou vídeo ao comportamento que demonstra. Identifique a origem da evidência, como dispositivo, emulador ou renderização de componente. Preserve anexos já publicados ao atualizar o PR. -->
