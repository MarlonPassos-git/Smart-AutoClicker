import { zoom, sleep, now, log, stop } from "klickr";
import { heroReferences } from "./profile.js";
import { found, number, snapshot, waitSnapshot, guard, position, tapStep, act, transition } from "./screen.js";
import { battleFinished, nextBurst } from "./policy.js";

const resultNames = ["result", "resultText"];
const hasResult = current => resultNames.some(name => found(current.results[name]));
const villageNames = ["village", "villageText"];
const hasVillage = current => villageNames.some(name => found(current.results[name]));
const statusNames = options => [...resultNames, "exitConfirm", "battle", "destruction",
    ...(options.finishMode === "saque total" ? ["goldLoot", "elixirLoot"] : [])];

export async function openBattle() {
    let opened = false;
    for (let attempt = 0; attempt < 3; attempt++) {
        const village = await waitSnapshot("vila antes da batalha", villageNames, hasVillage);
        await guard(found(village.results.village) ? "village" : "villageText", village);
        await transition("abrir batalhas", villageNames, hasVillage, current => [tapStep(position(current, "attackOpen"))]);
        // The game can ignore an opening tap; retry only after freshly recognizing the village again.
        const menu = await waitSnapshot("menu de batalhas aberto", ["search", "searchText"], current =>
            found(current.results.search) || found(current.results.searchText), { timeoutMs: 3000, intervalMs: 100, required: false });
        if (menu) { opened = true; break; }
    }
    if (!opened) await stop("O menu de batalhas não abriu após três tentativas confirmadas na vila.");
    await transition("procurar partida", ["search", "searchText"], current =>
        found(current.results.search) || found(current.results.searchText), current => [tapStep(position(current, "searchOpen"))]);
    await transition("confirmar o exército atual", ["attack"], current => found(current.results.attack),
        current => [tapStep(position(current, "attackConfirm"))]);
    const opponent = await waitSnapshot("oponente encontrado", ["opponent", "battle"], current => found(current.results.opponent), { timeoutMs: 60000 });
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

async function deployTroops(options) {
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
        const boundaries = heroReferences.map(name => current.results[name]).filter(found).map(result => result.x - (result.width ?? 0) / 2);
        if (boundaries.length === 0) await stop("Não reconheci o limite entre tropas e heróis na barra calibrada.");
        const boundary = Math.min(...boundaries);
        if (!Number.isSafeInteger(amount) || amount <= 0) {
            unreadable++;
            if (attempted === 0 && await now() >= initialDeadline) await stop("Não consegui ler a barra inicial de tropas.");
            if (attempted > 0 && unreadable >= 4) return false;
            await sleep(200);
            continue;
        }
        if (quantity.x >= boundary) return false;
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
                steps.push(tapStep(position(actionFrame, `deploy${(cursor + index) % 12 + 1}`), 20, 20));
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
        const steps = [tapStep(hero, 65, 60)];
        for (let index = 1; index <= 4; index++) {
            steps.push(tapStep(position(current, `heroDeploy${index}`), 40, index === 4 ? 180 : 60));
        }
        steps.push(tapStep(hero, 65, 150));
        if (await act(current, steps)) used.push(hero.x);
    }
    return false;
}

async function deploySpells(options) {
    let attempted = 0;
    while (attempted < 96) {
        const checked = await checkpoint(options, ["spell"]);
        if (checked.finished) return true;
        const current = checked.current;
        if (!found(current.results.spell)) return false;
        // Reselect from this frame in every block, including after pause or a changed card.
        const steps = [tapStep(current.results.spell, 150, 250)];
        for (let offset = 0; offset < 2; offset++) {
            steps.push(tapStep(position(current, `spellDeploy${(attempted + offset) % 12 + 1}`), 120, 150));
        }
        if (await act(current, steps)) attempted += 2;
    }
    await stop("O card de feitiço permaneceu disponível após 96 tentativas; confira a barra.");
}

export async function runBattle(options) {
    if (!await deployTroops(options) && !await deployHeroes(options) && !await deploySpells(options)) {
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
    const village = await waitSnapshot("vila após a batalha", villageNames, hasVillage, { timeoutMs: 30000, intervalMs: 100 });
    await guard(found(village.results.village) ? "village" : "villageText", village);
    await log("Batalha concluída e retorno à vila confirmado.");
}
