import { observe, profile, read, waitAny, waitFor, tap, parameters, stage, log, resumeWhen, stop } from "klickr";

// Capture three named images/regions in the target app: ready, completed and closeDialog.
// The default is observation only. Importing or validating the package never executes these operations.
const options = await parameters();
if (!Number.isInteger(options.cycles)) await stop("Máximo de ciclos precisa ser um número inteiro.");
const screen = await profile(await observe());
for (const name of ["ready", "completed", "closeDialog"]) {
    if (!screen.regions[name]) await stop(`Capture a imagem e a região ${name} antes de iniciar.`);
}
const query = name => ({ kind: "image", reference: name, region: screen.regions[name], threshold: 12 });

// A recognition-derived action is never retried with its old frame after pause/another observation.
async function clickCurrent(result) {
    try {
        await tap(result);
        return true;
    } catch (error) {
        if (error.code === "stale_observation") return false;
        throw error;
    }
}

async function readFresh(condition) {
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            return await read(await observe(), condition);
        } catch (error) {
            if (error.code !== "stale_observation") throw error;
        }
    }
    await stop("A observação mudou em três tentativas; confira a tela e inicie novamente.");
}

let completed = 0;
let recoveries = 0;
while (completed < options.cycles) {
    await stage("aguardando", `Aguardando tela pronta, conclusão ou diálogo. Ciclo ${completed + 1}.`);
    await resumeWhen(query("ready"));
    const outcome = await waitAny([
        { name: "ready", query: query("ready") },
        { name: "completed", query: query("completed") },
        { name: "dialog", query: query("closeDialog") }
    ], { timeoutMs: 10000, intervalMs: 50 });
    if (outcome.status === "timeout") await stop("Nenhuma tela prevista foi reconhecida em 10 segundos.");
    if (!options.execute) {
        await log(`Tela reconhecida: ${outcome.name}. Nenhum clique habilitado.`);
        await stop("Observação concluída. Ative Permitir cliques para executar o exemplo.");
    }
    if (outcome.name === "dialog") {
        await stage("recuperando", "Fechando o diálogo previsto pelo autor.");
        await resumeWhen(query("closeDialog"));
        if (recoveries >= 2) await stop("O diálogo reapareceu após duas recuperações.");
        if (!await clickCurrent(outcome.result)) continue;
        recoveries++;
        // Gesture completion can precede the target's redraw; do not count dismissal frames as new dialogs.
        await stage("confirmando recuperação", "Aguardando a tela prevista após fechar o diálogo.");
        await resumeWhen(query("ready"));
        const recovered = await waitAny([
            { name: "ready", query: query("ready") },
            { name: "completed", query: query("completed") }
        ], { timeoutMs: 10000, intervalMs: 50 });
        if (recovered.status === "timeout") await stop("O diálogo não levou a uma tela prevista em 10 segundos.");
        continue;
    }
    if (outcome.name === "ready") {
        await stage("executando", "Tela pronta; conferindo a ação.");
        // stage/resumeWhen are checkpoints too. Read again so a pause cannot leave a stale action.
        const fresh = await readFresh(query("ready"));
        if (fresh.status !== "found" || !await clickCurrent(fresh)) continue;
        await stage("confirmando", "Aguardando a confirmação da ação.");
        await resumeWhen(query("completed"));
        const confirmation = await waitFor(query("completed"), { timeoutMs: 10000, intervalMs: 50 });
        if (confirmation.status !== "found") await stop("A ação não teve confirmação na tela.");
    }
    completed++;
    recoveries = 0;
    await log(`Ciclos confirmados: ${completed}.`);
    if (options.completion === "parar" || completed >= options.cycles) break;
    await stage("novo ciclo", "Aguardando a tela pronta reaparecer.");
    await resumeWhen(query("ready"));
    const next = await waitFor(query("ready"), { timeoutMs: 30000, intervalMs: 100 });
    if (next.status !== "found") await stop("A tela pronta não reapareceu para o próximo ciclo.");
}
await stop(`Exemplo concluído: ${completed} ciclo(s) confirmado(s).`);
