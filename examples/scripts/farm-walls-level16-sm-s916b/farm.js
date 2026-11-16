import { log, stop } from "klickr";
import { found, number, waitSnapshot } from "./screen.js";
import { validateOptions, resourceRule, requiredResources } from "./policy.js";
import { openBattle, runBattle } from "./battle.js";
import { improveWalls } from "./walls.js";

function requiredBalances(options) {
    const needed = new Set();
    for (const rule of [options.improveWalls ? options.wallTrigger : "nunca", options.stopFarm]) {
        requiredResources(rule).forEach(resource => needed.add(resource));
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

function minimumReason(options, balances) {
    for (const resource of ["gold", "elixir"]) {
        const minimum = options[`${resource}Minimum`];
        if (minimum > 0 && balances[resource] <= minimum) return (`Saldo mínimo de ${resource} atingido: ${balances[resource]} <= ${minimum}.`);
    }
}

export async function runVillage(options, { attacks = 0, onAttempt = async () => {}, onConfirmed = async () => {} } = {}) {
    validateOptions(options, true);
    let battles = attacks;
    let walls = 0;
    while (true) {
        let balances = await villageBalances(options);
        const minimum = minimumReason(options, balances);
        if (minimum) return minimum;
        if (options.improveWalls && resourceRule(options.wallTrigger, balances, options) === true) {
            walls += await improveWalls(options);
            balances = await villageBalances(options);
            const minimum = minimumReason(options, balances);
            if (minimum) return minimum;
        }
        if (resourceRule(options.stopFarm, balances, options) === true) {
            return (`Limite de recursos atingido (${options.stopFarm}). Batalhas: ${battles}; muros confirmados: ${walls}.`);
        }
        if (options.maxBattles > 0 && battles >= options.maxBattles) {
            return (`Limite de ${options.maxBattles} batalha(s) atingido. Muros confirmados: ${walls}.`);
        }
        await log(`Entrada na batalha ${battles + 1}; saldos lidos: ${JSON.stringify(balances)}.`);
        const generation = await onAttempt();
        await openBattle(generation);
        await runBattle(options);
        battles++;
        await onConfirmed(battles);
    }
}

export async function runFarm(options) {
    await stop(await runVillage(options));
}
