import { log, stop } from "klickr";
import { found, number, waitSnapshot } from "./screen.js";
import { validateOptions, resourceRule } from "./policy.js";
import { openBattle, runBattle } from "./battle.js";
import { improveWalls } from "./walls.js";

function requiredBalances(options) {
    const needed = new Set();
    for (const rule of [options.improveWalls ? options.wallTrigger : "nunca", options.stopFarm]) {
        if (rule === "ouro cheio") needed.add("gold");
        else if (rule === "elixir cheio") needed.add("elixir");
        else if (rule === "qualquer depósito cheio" || rule === "ambos cheios") {
            needed.add("gold"); needed.add("elixir");
        }
    }
    if (options.goldMinimum > 0) needed.add("gold");
    if (options.elixirMinimum > 0) needed.add("elixir");
    return [...needed];
}

async function villageBalances(options) {
    const resources = requiredBalances(options);
    const current = await waitSnapshot("vila e saldos necessários", ["village", "villageText", ...resources.map(resource => `${resource}Balance`)],
        value => (found(value.results.village) || found(value.results.villageText)) &&
            resources.every(resource => Number.isFinite(number(value.results[`${resource}Balance`]))), { timeoutMs: 10000, intervalMs: 100 });
    return Object.fromEntries(resources.map(resource => [resource, number(current.results[`${resource}Balance`])]));
}

async function checkMinimums(options, balances) {
    for (const resource of ["gold", "elixir"]) {
        const minimum = options[`${resource}Minimum`];
        if (minimum > 0 && balances[resource] <= minimum) await stop(`Saldo mínimo de ${resource} atingido: ${balances[resource]} <= ${minimum}.`);
    }
}

export async function runFarm(options) {
    validateOptions(options);
    let battles = 0;
    let walls = 0;
    while (true) {
        let balances = await villageBalances(options);
        await checkMinimums(options, balances);
        if (options.improveWalls && resourceRule(options.wallTrigger, balances, options) === true) {
            walls += await improveWalls(options);
            balances = await villageBalances(options);
            await checkMinimums(options, balances);
        }
        if (resourceRule(options.stopFarm, balances, options) === true) {
            await stop(`Limite de recursos atingido (${options.stopFarm}). Batalhas: ${battles}; muros confirmados: ${walls}.`);
        }
        if (options.maxBattles > 0 && battles >= options.maxBattles) {
            await stop(`Limite de ${options.maxBattles} batalha(s) atingido. Muros confirmados: ${walls}.`);
        }
        await log(`Entrada na batalha ${battles + 1}; saldos lidos: ${JSON.stringify(balances)}.`);
        await openBattle();
        await runBattle(options);
        battles++;
    }
}
