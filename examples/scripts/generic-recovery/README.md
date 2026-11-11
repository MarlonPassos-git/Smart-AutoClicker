# Exemplo de espera e recuperação

O script reconhece uma tela pronta, uma confirmação de conclusão e um diálogo previsto. Por padrão, apenas informa a tela encontrada. Com **Permitir cliques** habilitado, fecha o diálogo, executa a ação e exige a confirmação antes de contar um ciclo.

1. Empacote esta pasta e importe o ZIP em **Scripts**.
2. Use **Capturar recurso**, com **Imagem e região**, para salvar `ready`, `completed` e `closeDialog` no aplicativo alvo. Os nomes distinguem maiúsculas e minúsculas.
3. Valide e inicie com **Permitir cliques** desabilitado para conferir as referências.
4. Habilite os cliques para executar a ação. Escolha entre parar na primeira conclusão ou esperar a tela pronta reaparecer para outro ciclo.

A recuperação aceita no máximo duas reaparições do diálogo. Depois de fechá-lo, aguarda uma tela pronta ou concluída por até 10 segundos, sem contar os frames da animação de saída como novas ocorrências. A confirmação da ação tem prazo de 10 segundos; o próximo ciclo, 30 segundos. Uma observação invalidada por pausa é descartada e lida novamente. O script não tenta fechar diálogos que o autor não cadastrou.

Da raiz do repositório, com um destino novo:

```sh
python3 scripts/package_script_example.py examples/scripts/generic-recovery build/script-examples/generic-recovery.zip
```

Consulte o [guia do modo Script](../../../docs/MODO-SCRIPT.md) para a API e o formato do pacote.
