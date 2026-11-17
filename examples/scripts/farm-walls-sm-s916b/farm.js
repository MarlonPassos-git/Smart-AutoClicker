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

const validBalance = value => Number.isSafeInteger(value) && value >= 0 && value <= 999999999;
const balanceContext = current => `${current.frame.width}:${current.frame.height}:${current.frame.resumeGeneration}`;

async function confirmStableBalance(current, resource, samples, history) {
    if (!history) return undefined;
    const candidates = [...new Set(samples.map(sample => number(sample.result)).filter(validBalance))];
    if (candidates.length !== 1) { history.delete(resource); return undefined; }
    const value = candidates[0];
    const previous = history.get(resource);
    const context = balanceContext(current);
    // A repeated call on the same observation is not independent evidence.
    if (previous?.context === context && previous.frame === current.frame.frame) return undefined;
    const consecutive = previous?.context === context && previous.value === value && previous.frame < current.frame.frame
        ? previous.consecutive + 1 : 1;
    const query = samples.find(sample => number(sample.result) === value).query;
    history.set(resource, { value, consecutive, context, frame: current.frame.frame, query });
    await log(JSON.stringify({ event: "balance_stability", resource, value, consecutive, required: 3,
        frame: current.frame.frame, resumeGeneration: current.frame.resumeGeneration }));
    if (consecutive < 3) return undefined;
    history.delete(resource);
    return value;
}

/** Confirm a balance across crops, or three fresh frames using a caller-owned, village-local history.
 * Example: await readResourceBalance(current, "gold", history). Omit history to require same-frame agreement. */
export async function readResourceBalance(current, resource, history) {
    const base = current.calibration.regions[`${resource}Balance`];
    const accepted = value => { history?.delete(resource); return value; };
    if (history?.get(resource)?.context !== balanceContext(current)) history?.delete(resource);
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
    if (fast !== undefined) return accepted(fast);
    const samples = [
        { query: { kind: "number", reference: "decimal", region: base, threshold: 85 }, result: current.results[`${resource}Balance`] },
        { query: probe, result: first[0] },
    ];
    const previous = history?.get(resource);
    if (previous) {
        const preferred = await readAll(current.frame, [previous.query]);
        const focused = [...samples, { query: previous.query, result: preferred[0] }];
        const candidates = focused.map(sample => number(sample.result)).filter(validBalance);
        // Reuse only a readable, uncontradicted crop. Otherwise run the complete recovery again.
        if (number(preferred[0]) === previous.value && candidates.every(value => value === previous.value)) {
            return confirmStableBalance(current, resource, focused, history);
        }
        history.delete(resource);
    }
    const alternatives = await readAll(current.frame, queries);
    samples.push(...alternatives.map((result, index) => ({ query: queries[index], result })));
    const candidates = [...readings, ...alternatives.map(number)];
    const normal = balanceConsensus(candidates);
    if (normal !== undefined) {
        await log(JSON.stringify({ event: "balance_read", resource, value: normal, method: "text_blocks",
            candidates: candidates.filter(value => value !== undefined) }));
        return accepted(normal);
    }
    // Single-line recognition complements localization; it does not replace readable text blocks.
    const lines = await readAll(current.frame, whiteQueries);
    samples.push(...lines.map((result, index) => ({ query: whiteQueries[index], result })));
    const lineCandidates = lines.map(number);
    const value = balanceConsensus(lineCandidates);
    await log(JSON.stringify({ event: "balance_read", resource, value: value ?? null, method: "white_integer_line",
        candidates: lineCandidates.filter(value => value !== undefined),
        blockCandidates: candidates.filter(value => value !== undefined) }));
    if (value !== undefined) return accepted(value);
    return confirmStableBalance(current, resource, samples, history);
}

async function villageBalances(options) {
    await confirmVillage();
    const resources = requiredBalances(options);
    const deadline = await now() + 10000;
    let missing = resources;
    let homeSeen = false;
    // Never carry balance evidence across resource decisions, villages, or executions.
    const history = new Map();
    do {
        try {
            const current = await snapshot(["village", "villageText", ...resources.map(resource => `${resource}Balance`)]);
            const home = found(current.results.village) || found(current.results.villageText);
            homeSeen ||= home;
            if (home) {
                const balances = {};
                for (const resource of resources) balances[resource] = await readResourceBalance(current, resource, history);
                missing = resources.filter(resource => balances[resource] === undefined);
                if (!missing.length) {
                    await guard(found(current.results.village) ? "village" : "villageText", current);
                    return balances;
                }
            } else history.clear();
        } catch (error) {
            if (error.code !== "stale_observation") throw error;
            history.clear();
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
