import { observe, loadState, saveState, stage, log, stop, progress } from "klickr";
import { validateOptions } from "./policy.js";
import { runVillage } from "./farm.js";
import { runBattle } from "./battle.js";
import { waitSnapshot, found } from "./screen.js";
import { configuredVillages } from "./configuration.js";
import { currentIdentity, switchVillage } from "./villages.js";

function validateRound(round, options) {
    if (round.version !== 1 || JSON.stringify(round.options) !== JSON.stringify(options) || !Array.isArray(round.villages) || round.villages.length > 64 ||
        !["discovery", "ready", "completed"].includes(round.stage) || (round.stage !== "discovery" && round.villages.length === 0)) {
        throw new Error("Rodada incompatível ou inválida; preserve o registro e escolha Nova rodada.");
    }
    const ids = new Set();
    for (const village of round.villages) {
        if (typeof village.id !== "string" || ids.has(village.id) || !Number.isSafeInteger(village.attacks) || village.attacks < 0 ||
            !["pending", "running", "completed"].includes(village.status)) throw new Error("Progresso de vila inválido; escolha Nova rodada.");
        ids.add(village.id);
    }
    if (round.stage === "completed" && round.villages.some(village => village.status !== "completed")) {
        throw new Error("Rodada concluída com vilas pendentes: progresso inválido; registro preservado.");
    }
    if (round.pending != null) {
        const village = round.villages.find(village => village.id === round.pending.village);
        if (round.stage !== "ready" || !["switch", "attack"].includes(round.pending.kind) || !village || village.status === "completed") {
            throw new Error("Transição pendente inválida; registro preservado. Escolha Nova rodada.");
        }
    }
}

function summary(round) {
    const labels = { pending: "pendente", running: "em andamento", completed: "concluída" };
    return round.villages.map(village => {
        const status = round.pending?.kind === "attack" && round.pending.village === village.id ? "ataque aguardando confirmação" : labels[village.status];
        return `${village.name}: ${village.attacks} ataque(s), ${status}${village.reason ? ` — ${village.reason}` : ""}`;
    });
}

async function checkpoint(round, completed = false) {
    round.updatedAt = new Date().toISOString();
    round.details = summary(round);
    await saveState(round, { completed });
}

async function requireHome(round) {
    const names = ["village", "villageText", "result", "resultText", "battle"];
    const current = await waitSnapshot("tela da recuperação", names,
        value => names.some(name => found(value.results[name])),
        { timeoutMs: 30000, intervalMs: 200, required: false });
    if (current && (found(current.results.village) || found(current.results.villageText))) return "home";
    if (current && (found(current.results.result) || found(current.results.resultText))) return "result";
    round.reason = "Batalha ou transição pendente. Finalize manualmente e volte à vila para conferir a identidade e resolver a contagem.";
    await checkpoint(round);
    await stop(round.reason);
}

async function recoverAttack(round, automation) {
    const pending = round.pending;
    const village = round.villages.find(item => item.id === pending.village);
    if (!village || village.status === "completed" || pending.confirmedBefore !== village.attacks) throw new Error("Tentativa pendente sem vila em andamento; registro preservado.");
    const screen = await requireHome(round);
    if (screen === "result") {
        await (automation.runBattle || runBattle)(round.options);
        const id = await automation.currentIdentity();
        if (id !== village.id) throw new Error(`Resultado pertence à conta ${id}, esperada ${village.id}; contagem não alterada.`);
        village.attacks++;
    } else {
        const id = await automation.currentIdentity();
        if (id !== village.id) throw new Error(`Conta atual ${id}, esperada ${village.id}; volte à conta correta antes de continuar.`);
        const answer = round.recovery?.answer;
        if (answer !== "count" && answer !== "discard") {
            village.reason = "Ataque de resultado incerto; aguardando resolução.";
            round.recovery = {
                message: `Não foi possível confirmar o último ataque de ${village.name}. Confira o jogo e escolha como resolver a contagem. Nenhum ataque será repetido automaticamente.`,
                choices: [{ id: "count", label: "Confirmar que o ataque terminou e contar +1" },
                    { id: "discard", label: "Confirmar que não houve ataque; manter a contagem" }],
            };
            await checkpoint(round);
            await stop(round.recovery.message);
        }
        if (answer === "count") village.attacks++;
        await log(`Pendência resolvida pelo usuário: ${answer}, vila ${village.name}.`);
    }
    round.pending = null;
    round.recovery = null;
    village.reason = null;
    await checkpoint(round);
}

/** One finite round. Dependencies are injectable for deterministic JS integration coverage. */
export async function runRound(options, automation = { currentIdentity, configuredVillages, switchVillage, runVillage }) {
    validateOptions(options);
    let round = await loadState();
    if (!round) {
        round = { version: 1, id: `${Date.now()}`, options, villages: [], stage: "discovery", pending: null, createdAt: new Date().toISOString() };
        await checkpoint(round);
    }
    validateRound(round, options);
    try {
        if (round.stage === "discovery") {
            round.villages = await automation.configuredVillages(options);
            const current = await automation.currentIdentity();
            // Prefer the already open selected account without changing stable identities.
            const index = round.villages.findIndex(village => village.id === current);
            if (index > 0) round.villages.unshift(...round.villages.splice(index, 1));
            round.stage = "ready";
            await checkpoint(round);
        }
        if (round.pending?.kind === "attack") await recoverAttack(round, automation);
        for (let index = 0; index < round.villages.length; index++) {
            const village = round.villages[index];
            if (village.status === "completed") continue;
            round.current = village.id;
            round.pending = { kind: "switch", village: village.id };
            await checkpoint(round);
            const current = await automation.currentIdentity();
            if (current !== village.id) await automation.switchVillage(village);
            const confirmed = await automation.currentIdentity();
            if (confirmed !== village.id) throw new Error(`Conta ${confirmed}; esperada ${village.id} antes de atacar.`);
            round.pending = null;
            village.status = "running";
            village.reason = null;
            await checkpoint(round);
            const villageOptions = { ...options, ...village.capacities, improveWalls: options.multivillage ? false : options.improveWalls };
            const report = () => progress(`Vila ${index + 1}/${round.villages.length}\n${village.attacks}/${options.maxBattles || "∞"} ataques\n${village.name}`);
            await report();
            village.reason = await automation.runVillage(villageOptions, {
                attacks: village.attacks,
                onAttempt: async () => {
                    // Bind the confirmed account to a pause generation. A pause between this check and
                    // the first battle gesture invalidates that authorization, even on another valid village.
                    const before = await observe();
                    const identity = await automation.currentIdentity();
                    const after = await observe();
                    if (identity !== village.id || before.resumeGeneration !== after.resumeGeneration) {
                        throw new Error("Conta ou pausa mudou durante a autorização do ataque; continue para conferir novamente.");
                    }
                    round.recovery = null;
                    round.pending = { kind: "attack", village: village.id, confirmedBefore: village.attacks };
                    await checkpoint(round);
                    return after.resumeGeneration;
                },
                onConfirmed: async count => {
                    village.attacks = count;
                    round.pending = null;
                    await checkpoint(round);
                    await report();
                },
            });
            if (round.pending) throw new Error("A tentativa de ataque terminou sem confirmação; progresso preservado.");
            village.status = "completed";
            await checkpoint(round);
            await log(round.details[index]);
        }
        round.stage = "completed";
        round.reason = "Todas as vilas da rodada foram concluídas.";
        await checkpoint(round, true);
        await stage("Rodada concluída", `${round.reason} Total: ${round.villages.length} vila(s).`);
        return;
    } catch (error) {
        // stop() and service cancellation are host control signals. A pending checkpoint always predates effects.
        round.reason = error.message;
        const interrupted = round.villages.find(village => village.id === round.current);
        if (interrupted && interrupted.status !== "completed") interrupted.reason = error.message;
        if (round.stage !== "completed") await checkpoint(round);
        throw error;
    }
}
