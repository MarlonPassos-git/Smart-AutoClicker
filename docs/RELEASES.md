# Como publicar uma versão

O workflow `Publicar versão Android` valida a versão, executa os testes, gera os
APKs, calcula os hashes SHA-256 e cria a página da versão no GitHub com as notas
extraídas do `CHANGELOG.md`.

## Preparar a versão

1. Atualize `versionCode` e `versionName` em
   `smartautoclicker/build.gradle.kts`.
2. Crie a seção correspondente no `CHANGELOG.md`, em português.
3. Execute `./gradlew testFDroidDebugUnitTest`.
4. Envie o commit para o GitHub.
5. Crie e envie uma tag igual ao `versionName`:

```bash
git tag -a 4.0.0-beta05 -m "Klick'r 4.0.0-beta05"
git push origin 4.0.0-beta05
```

Também é possível abrir **Actions → Publicar versão Android → Run workflow** e
informar a versão. Nesse modo, o workflow cria a tag apontando para o commit
selecionado.

## Validações automáticas

A publicação é interrompida quando:

- a versão não segue o formato semântico esperado;
- o `versionName` é diferente da tag ou da versão informada;
- o `versionCode` não aumentou desde a versão anterior;
- não existe uma seção preenchida para a versão no `CHANGELOG.md`;
- a versão já possui uma Release no GitHub;
- os testes ou a compilação falham;
- uma versão estável não possui assinatura de release configurada.

## Assinatura dos APKs

Para uma versão beta, a ausência dos secrets gera APKs de desenvolvimento,
assinados pela chave de debug do Android. Eles usam o pacote
`com.buzbuz.smartautoclicker.debug` e podem coexistir com a versão oficial.

Versões estáveis exigem estes secrets no repositório:

- `RELEASE_KEYSTORE`: conteúdo ASCII do keystore criptografado;
- `RELEASE_KEYSTORE_PASSPHRASE`: senha usada na criptografia;
- `SIGNING_STORE_PASSWORD`: senha do keystore Android;
- `SIGNING_KEY_ALIAS`: alias da chave;
- `SIGNING_KEY_PASSWORD`: senha da chave.

Para preparar o conteúdo de `RELEASE_KEYSTORE`:

```bash
gpg --armor --symmetric --cipher-algo AES256 \
  --output smartautoclicker.jks.asc smartautoclicker.jks
```

Guarde o keystore e todas as senhas em local seguro. Perder essa chave impede
que futuras versões atualizem APKs assinados por ela.

O repositório também precisa permitir que o `GITHUB_TOKEN` escreva conteúdo em
**Settings → Actions → General → Workflow permissions**.
