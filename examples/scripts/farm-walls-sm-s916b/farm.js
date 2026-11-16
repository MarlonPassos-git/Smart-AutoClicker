import { log, stop, readAll, now, sleep } from "klickr";
import { found, number, snapshot, guard } from "./screen.js";
import { validateOptions, resourceRule, requiredResources } from "./policy.js";
import { openBattle, runBattle } from "./battle.js";
import { improveWalls } from "./walls.js";
import { confirmVillage } from "./villages.js";

function requiredBalances(options) {
    const needed = new Set();
    for (const rule of [options.improveWalls ? options.wallTrigger : "nunca", options.stopFarm]) {
        requiredResources(rule).forEach(resource => needed.add(resource));
    }
    if (options.goldMinimum > 0) needed.add("gold");
    if (options.elixirMinimum > 0) needed.add("elixir");
    return [...needed];
}

/** Accept the unique most frequent integer only after two distinct crops agree; e.g. [2,2,3] -> 2. */
export function balanceConsensus(values) {
    const votes = new Map();
    for (const value of values) if (Number.isSafeInteger(value) && value >= 0 && value <= 999999999) votes.set(value, (votes.get(value) || 0) + 1);
    const ranked = [...votes].sort((a, b) => b[1] - a[1]);
    return ranked[0]?.[1] >= 2 && ranked[0][1] > (ranked[1]?.[1] || 0) ? ranked[0][0] : undefined;
}

/** Read one balance with agreement across calibrated crops; e.g. await readResourceBalance(current, "dark"). */
export async function readResourceBalance(current, resource) {
    const base = current.calibration.regions[`${resource}Balance`];
    const scale = current.frame.width / 2340;
    const queries = [];
    for (const inset of [0, 10, 20, 30]) for (const trim of [-5, -2, 0, 2]) {
        if (inset === 0 && trim === 0) continue;
        const region = { ...base, left: base.left + Math.round(inset * scale),
            top: base.top + Math.round(trim * scale), bottom: base.bottom - Math.round(trim * scale) };
        queries.push({ kind: "number", reference: "decimal", region, threshold: 85 });
    }
    const whiteQueries = [{ kind: "number", reference: "balanceInteger", region: base, threshold: 85 },
        ...queries.map(query => ({ ...query, reference: "balanceInteger" }))];
    const probeIndex = 8; // Different horizontal and vertical context, without removing any digits.
    const probe = queries.splice(probeIndex, 1)[0];
    const first = await readAll(current.frame, [probe]);
    const readings = [number(current.results[`${resource}Balance`]), number(first[0])];
    const fast = balanceConsensus(readings);
    if (fast !== undefined) return fast;
    const alternatives = await readAll(current.frame, queries);
    const candidates = [...readings, ...alternatives.map(number)];
    const normal = balanceConsensus(candidates);
    if (normal !== undefined) {
        await log(JSON.stringify({ event: "balance_read", resource, value: normal, method: "text_blocks",
            candidates: candidates.filter(value => value !== undefined) }));
        return normal;
    }
    // Single-line recognition complements localization; it does not replace readable text blocks.
    const lines = await readAll(current.frame, whiteQueries);
    const lineCandidates = lines.map(number);
    const value = balanceConsensus(lineCandidates);
    await log(JSON.stringify({ event: "balance_read", resource, value: value ?? null, method: "white_integer_line",
        candidates: lineCandidates.filter(value => value !== undefined),
        blockCandidates: candidates.filter(value => value !== undefined) }));
    return value;
}

async function villageBalances(options) {
    await confirmVillage();
    const resources = requiredBalances(options);
    const deadline = await now() + 10000;
    let missing = resources;
    let homeSeen = false;
    do {
        try {
            const current = await snapshot(["village", "villageText", ...resources.map(resource => `${resource}Balance`)]);
            const home = found(current.results.village) || found(current.results.villageText);
            homeSeen ||= home;
            if (home) {
                const balances = {};
                for (const resource of resources) balances[resource] = await readResourceBalance(current, resource);
                missing = resources.filter(resource => balances[resource] === undefined);
                if (!missing.length) {
                    await guard(found(current.results.village) ? "village" : "villageText", current);
                    return balances;
                }
            }
        } catch (error) {
            if (error.code !== "stale_observation") throw error;
        }
        await sleep(200);
    } while (await now() < deadline);
    await stop(`Saldos sem concordância: ${missing.join(", ") || "nenhum"}. Vila reconhecida: ${homeSeen ? "sim" : "não"}. Rodada preservada; nenhum próximo ataque autorizado.`);
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
        if (!options.improveWalls && options.maxBattles > 0 && battles >= options.maxBattles) {
            return `Limite de ${options.maxBattles} batalha(s) atingido. Muros confirmados: ${walls}.`;
        }
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
