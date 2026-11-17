import { zoom, sleep, now, log, stop, stage } from "klickr";
import { heroReferences } from "./profile.js";
import { found, number, snapshot, waitSnapshot, guard, position, tapStep, act, transition } from "./screen.js";
import { battleFinished, nextBurst } from "./policy.js";
import { confirmVillage } from "./villages.js";
import { readSpellCards } from "./spells.js";

const resultNames = ["result", "resultText"];
const hasResult = current => resultNames.some(name => found(current.results[name]));
const villageNames = ["village", "villageText"];
const hasVillage = current => villageNames.some(name => found(current.results[name]));
const statusNames = options => [...resultNames, "exitConfirm", "battle", "destruction",
    ...(options.finishMode === "saque total" ? ["goldLoot", "elixirLoot"] : [])];

export function requireAccountGeneration(current, expected) {
    if (expected !== undefined && current.frame.resumeGeneration !== expected) {
        throw new Error("Pausa durante a entrada no ataque; confirme novamente a conta continuando a rodada.");
    }
}

export async function openBattle(expectedGeneration) {
    let opened = false;
    for (let attempt = 0; attempt < 3; attempt++) {
        const village = await waitSnapshot("vila antes da batalha", villageNames, hasVillage);
        await guard(found(village.results.village) ? "village" : "villageText", village);
        await transition("abrir batalhas", villageNames, hasVillage, current => { requireAccountGeneration(current, expectedGeneration); return [tapStep(position(current, "attackOpen"))]; });
        // The game can ignore an opening tap; retry only after freshly recognizing the village again.
        const menu = await waitSnapshot("menu de batalhas aberto", ["search", "searchText"], current =>
            found(current.results.search) || found(current.results.searchText), { timeoutMs: 3000, intervalMs: 100, required: false });
        if (menu) { opened = true; break; }
    }
    if (!opened) await stop("O menu de batalhas não abriu após três tentativas confirmadas na vila.");
    await transition("procurar partida", ["search", "searchText"], current =>
        found(current.results.search) || found(current.results.searchText), current => { requireAccountGeneration(current, expectedGeneration); return [tapStep(position(current, "searchOpen"))]; });
    await transition("confirmar o exército atual", ["attack"], current => found(current.results.attack),
        current => { requireAccountGeneration(current, expectedGeneration); return [tapStep(position(current, "attackConfirm"))]; });
    // CV19 matchmaking can remain in clouds for over a minute. Wait without sending
    // gestures or counting a battle until the opponent controls are recognized.
    const opponent = await waitSnapshot("oponente encontrado", ["opponent", "battle"], current => found(current.results.opponent), { timeoutMs: 180000, intervalMs: 200 });
    await guard("battle", opponent);
    const center = position(opponent, "zoomCenter");
    await zoom({ x: center.x, y: center.y }, "out", 400, 350);
    await zoom({ x: center.x, y: center.y }, "out", 400, 350);
    await sleep(250);
}

async function finishEarly(current) {
    if (!found(current.results.exitConfirm)) {
        if (!await act(current, [tapStep(position(current, "exitBattle"))])) return false;
    }
    await transition("confirmar a retirada", ["exitConfirm"], value => found(value.results.exitConfirm),
        value => [tapStep(value.results.exitConfirm)]);
    await waitSnapshot("resultado da batalha", resultNames, hasResult, { timeoutMs: 30000, intervalMs: 100 });
    return true;
}

// Navigation/goal checks have priority over deployment, using the same frame as each action block.
async function checkpoint(options, extra = []) {
    const current = await waitSnapshot("interface do ataque", [...statusNames(options), ...extra], value =>
        hasResult(value) || found(value.results.exitConfirm) || found(value.results.battle), { timeoutMs: 3000 });
    if (hasResult(current)) return { current, finished: true };
    if (found(current.results.exitConfirm) || battleFinished(options, number(current.results.destruction),
        number(current.results.goldLoot), number(current.results.elixirLoot))) {
        if (await finishEarly(current)) return { current, finished: true };
        return checkpoint(options, extra);
    }
    return { current, finished: false };
}

async function deployTroops(options, spellLayout) {
    let attempted = 0;
    let cursor = 0;
    let previous = null;
    let noProgress = 0;
    let unreadable = 0;
    const initialDeadline = await now() + 3000;
    while (true) {
        const checked = await checkpoint(options, ["troops", ...heroReferences]);
        if (checked.finished) return true;
        const current = checked.current;
        const quantity = current.results.troops;
        const amount = number(quantity);
        const heroes = heroReferences.map(name => current.results[name]).filter(found);
        const boundaries = heroes.map(result => result.x - (result.width ?? 0) / 2);
        if (boundaries.length === 0) await stop("Não reconheci o limite entre tropas e heróis na barra calibrada.");
        const boundary = Math.min(...boundaries);
        spellLayout.leftRatio = Math.max(...heroes.map(result => result.x + (result.width ?? 0) / 2)) / current.frame.width;
        if (!Number.isSafeInteger(amount) || amount <= 0) {
            unreadable++;
            if (attempted === 0 && await now() >= initialDeadline) await stop("Não consegui ler a barra inicial de tropas.");
            if (attempted > 0 && unreadable >= 4) return false;
            await sleep(200);
            continue;
        }
        if (quantity.x >= boundary) { spellLayout.expected = true; return false; }
        unreadable = 0;
        if (previous && quantity.x <= previous.x + 60 && amount >= previous.amount) noProgress++;
        else noProgress = 0;
        if (noProgress >= 3) await stop("A quantidade de tropas não diminuiu após três tentativas de lançamento.");
        previous = { x: quantity.x, amount };
        let remaining = amount;
        let first = true;
        let actionFrame = current;
        const generation = current.frame.resumeGeneration;
        while (remaining > 0) {
            if (!first) {
                const checked = await checkpoint(options);
                if (checked.finished) return true;
                actionFrame = checked.current;
                // A resumed script must re-read/reselect its card, not continue a remembered selection.
                if (actionFrame.frame.resumeGeneration !== generation) { previous = null; break; }
            }
            const count = nextBurst(remaining, attempted);
            if (count === 0) await stop("Limite de 600 tentativas de lançamento de tropas atingido.");
            const steps = first ? [tapStep(quantity, 20, 40)] : [];
            for (let index = 0; index < count; index++) {
                steps.push(tapStep(position(actionFrame, `deploy${(cursor + index) % 12 + 1}`), 20, 10));
            }
            if (!await act(actionFrame, steps)) { previous = null; break; }
            attempted += count; remaining -= count; cursor = (cursor + count) % 12; first = false;
        }
    }
}

async function deployHeroes(options) {
    const used = [];
    while (used.length < 6) {
        const checked = await checkpoint(options, heroReferences);
        if (checked.finished) return true;
        const current = checked.current;
        const hero = heroReferences.map(name => current.results[name]).find(result =>
            found(result) && !used.some(x => Math.abs(x - result.x) <= 60));
        if (!hero) return false;
        const steps = [tapStep(hero, 40, 40)];
        for (let index = 1; index <= 4; index++) {
            steps.push(tapStep(position(current, `heroDeploy${index}`), 30, index === 4 ? 60 : 30));
        }
        steps.push(tapStep(hero, 40, 60));
        if (await act(current, steps)) used.push(hero.x);
    }
    return false;
}

async function deploySpells(options, layout) {
    let attempted = 0;
    let conflicts = 0;
    let unreadable = 0;
    let noProgress = 0;
    let previous = null;
    const slots = [];
    const sameSlot = (a, b) => Math.abs(a.xRatio - b.xRatio) <= 60 / 2340;
    while (true) {
        const checked = await checkpoint(options);
        if (checked.finished) return true;
        const current = checked.current;
        let cards;
        try { cards = await readSpellCards(current, layout.leftRatio, slots); }
        catch (error) {
            if (error.code === "conflicting_spell_counter") {
                await log(JSON.stringify({ event: "spell_counter_conflict", attempt: ++conflicts, message: error.message }));
                if (conflicts >= 3) await stop(error.message);
                await stage("Conferindo feitiços", "Leituras divergentes; tentando novamente sem lançar feitiços.");
                await sleep(200);
                continue;
            }
            if (error.code !== "stale_observation") throw error;
            previous = null; noProgress = 0; continue;
        }
        conflicts = 0;
        for (const card of cards) {
            const slot = slots.find(slot => sameSlot(slot, card));
            if (slot) slot.quantity = card.quantity;
            else slots.push({ xRatio: card.xRatio, quantity: card.quantity });
        }
        await log(JSON.stringify({ event: "spell_counters_observed", cards: cards.map(({quantity, xRatio}) => ({quantity, xRatio})), slots }));
        const missing = slots.some(slot => slot.quantity > 0 && !cards.some(card => sameSlot(slot, card)));
        if (missing || (cards.length === 0 && (layout.expected || attempted > 0))) {
            if (++unreadable >= 3) await stop("Quantidade de feitiço ilegível em três observações; não foi considerada zero.");
            await sleep(200); continue;
        }
        if (previous && previous.generation === current.frame.resumeGeneration) {
            const updated = cards.find(card => sameSlot(card, previous));
            if (!updated) {
                if (++unreadable >= 3) await stop("Não confirmei o contador do último feitiço; lançamento interrompido.");
                await sleep(200); continue;
            }
            noProgress = updated.quantity >= previous.quantity ? noProgress + 1 : 0;
            if (noProgress >= 3) await stop("A quantidade de feitiços não diminuiu após três tentativas; confira os alvos permitidos.");
        } else noProgress = 0;
        unreadable = 0;
        const card = cards.find(card => card.quantity > 0);
        if (!card) {
            await log(JSON.stringify({ event: "spell_deployment_completed", attempted, counters: cards.map(card => card.quantity) }));
            return false;
        }
        const count = Math.min(card.quantity, 2, 600 - attempted);
        if (count <= 0) await stop("Limite de 600 tentativas de lançamento de feitiços atingido.");
        await stage("lançar feitiços", `Card com ${card.quantity} feitiço(s); lançando ${count} e conferindo o contador.`);
        // Selection and casts use this observation; odd quantities never produce an extra cast.
        const selection = { ...card.at, y: current.calibration.regions.troops.bottom + Math.round(10 * current.frame.width / 2340) };
        const steps = [tapStep(selection, 65, 60)];
        for (let offset = 0; offset < count; offset++) steps.push(tapStep(position(current, `spellDeploy${(attempted + offset) % 12 + 1}`), 100, 150));
        if (await act(current, steps)) {
            attempted += count;
            previous = { ...card, generation: current.frame.resumeGeneration };
            await log(JSON.stringify({ event: "spell_cast_attempt", quantityBefore: card.quantity, count, attempted }));
        } else { previous = null; noProgress = 0; }
    }
}

export async function runBattle(options) {
    const spellLayout = {};
    const startedAt = await now();
    await stage("lançar tropas", "Tropas primeiro; depois heróis e feitiços.");
    let finished = await deployTroops(options, spellLayout);
    const troopsAt = await now();
    if (!finished) {
        await stage("lançar heróis", "Posicionando os heróis e ativando habilidades.");
        finished = await deployHeroes(options);
    }
    const heroesAt = await now();
    await log(JSON.stringify({ event: "army_deployment", troopsMs: troopsAt - startedAt,
        heroesMs: heroesAt - troopsAt, elapsedMs: heroesAt - startedAt, completed: !finished, targetMs: 10000 }));
    if (!finished) {
        await stage("lançar feitiços", "Conferindo os contadores de feitiços.");
        finished = await deploySpells(options, spellLayout);
    }
    // A result or the configured battle goal still takes priority at every deployment checkpoint.
    if (!finished) {
        const deadline = await now() + 240000;
        while (await now() < deadline) {
            if ((await checkpoint(options)).finished) break;
            await sleep(100);
        }
    }
    await transition("voltar do resultado à vila", resultNames, hasResult,
        current => [tapStep(position(current, "resultReturn"))], { timeoutMs: 10000 });
    // The game's returning clouds and animated balances must settle before resource decisions.
    await sleep(3000);
    const village = await confirmVillage();
    await guard(found(village.results.village) ? "village" : "villageText", village);
    await log("Batalha concluída e retorno à vila confirmado.");
}
