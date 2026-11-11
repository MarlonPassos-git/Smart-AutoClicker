import { zoom, sleep, log, stop } from "klickr";
import { all, found, number, snapshot, waitSnapshot, guard, position, tapStep, act, transition } from "./screen.js";
import { canReserve } from "./policy.js";

const resources = ["gold", "elixir"];
const selected = ["wallTitle", "selected18"];
const costs = ["goldCost", "elixirCost"];
const batchCosts = ["goldBatchCost", "elixirBatchCost"];
const priceNames = (resource, batch = false) => ["Available", "Insufficient"]
    .flatMap(state => [1, 2].map(index => `${resource}${batch ? "Batch" : ""}${state}${index}`));
const balanceName = resource => `${resource}Balance`;
const reserveFor = (options, resource) => options[`${resource}Reserve`];

function availability(current, resource, batch = false) {
    const prefix = `${resource}${batch ? "Batch" : ""}`;
    if (all(current, [`${prefix}Available1`, `${prefix}Available2`])) return true;
    if (all(current, [`${prefix}Insufficient1`, `${prefix}Insufficient2`])) return false;
    return undefined;
}

function equalCosts(current, names, expected) {
    const gold = number(current.results[names[0]]);
    const elixir = number(current.results[names[1]]);
    return Number.isSafeInteger(gold) && gold > 0 && gold === elixir && (expected === undefined || gold === expected);
}

async function checkBuilder() {
    const current = await waitSnapshot("construtor livre", ["builderOne", "buildersFree", "builders"], current =>
        found(current.results.builderOne) || found(current.results.buildersFree) || number(current.results.builders) === 0);
    if (number(current.results.builders) === 0 && !found(current.results.builderOne) && !found(current.results.buildersFree)) {
        await stop("Nenhum construtor livre para melhorar muros.");
    }
}

async function selectWall() {
    for (let sector = 1; sector <= 4; sector++) {
        const village = await snapshot(["village", "villageText"]);
        if (!found(village.results.village) && !found(village.results.villageText)) await stop("A vila deixou de ser reconhecida antes de procurar muros.");
        await guard(found(village.results.village) ? "village" : "villageText", village);
        // These are profile coordinates, not positions retained from a previous recognition.
        const center = position(village, "zoomCenter");
        const zoomCenter = { x: center.x, y: center.y };
        await zoom(zoomCenter, "out", 400, 350);
        await zoom(zoomCenter, "out", 400, 350);
        const target = position(village, `scan${sector}`);
        await zoom({ x: target.x, y: target.y }, "in", 100, 350);
        const candidate = await waitSnapshot(`muro elegível no setor ${sector}`, ["wall"], current => found(current.results.wall),
            { timeoutMs: 4200, intervalMs: 100, required: false });
        if (!candidate) continue;
        if (await act(candidate, [tapStep(candidate.results.wall)])) return true;
        sector--; // A pause before the action did not select anything; revalidate this sector.
    }
    await log("Nenhum muro elegível reconhecido nos quatro setores calibrados; voltando ao farm.");
    return false;
}

async function inspectSelected(options) {
    const names = [...selected, ...costs, "more", ...resources.flatMap(resource => priceNames(resource)),
        ...resources.filter(resource => reserveFor(options, resource) > 0).map(balanceName)];
    const current = await waitSnapshot("muro selecionado, preços e reservas", names, current => {
        if (!all(current, selected)) return false;
        const gold = number(current.results.goldCost);
        const elixir = number(current.results.elixirCost);
        // Preserve divergent valid readings for an explicit error instead of silently retrying one currency.
        return Number.isSafeInteger(gold) && gold > 0 && Number.isSafeInteger(elixir) && elixir > 0 &&
            resources.every(resource => availability(current, resource) !== undefined &&
                (reserveFor(options, resource) === 0 || Number.isFinite(number(current.results[balanceName(resource)]))));
    });
    if (!equalCosts(current, costs)) await stop("Os custos de ouro e elixir do muro não conferem.");
    await guard("wallTitle", current);
    return current;
}

async function readBatch(options, resource, expected, previous) {
    const names = ["batchRemove", "selected18", "batchAdd", ...batchCosts, ...priceNames(resource, true)];
    if (reserveFor(options, resource) > 0) names.push(balanceName(resource));
    return waitSnapshot(`lote de muros em ${resource}`, names, current =>
        all(current, ["batchRemove", "selected18"]) &&
        (equalCosts(current, batchCosts, expected) || previous !== undefined && equalCosts(current, batchCosts, previous)) &&
        availability(current, resource, true) !== undefined &&
        (reserveFor(options, resource) === 0 || Number.isFinite(number(current.results[balanceName(resource)]))));
}

/** Confirms the currency and exact total before authorizing a payment, e.g. `confirmPayment('gold', 8400000, true)`. */
export async function confirmPayment(resource, total, batch) {
    const names = batch ? ["batchTitle", "batchOk", "batchConfirmCost", `${resource}Label`] :
        ["singleTitle", `${resource}Confirm`, "singleConfirmCost", "confirmGreen1", "confirmGreen2"];
    const title = batch ? "batchTitle" : "singleTitle";
    await transition(`confirmação exata de ${total} em ${resource}`, names, current =>
        all(current, names.filter(name => !name.endsWith("Cost"))) &&
        number(current.results[batch ? "batchConfirmCost" : "singleConfirmCost"]) === total,
    current => [tapStep(batch ? current.results.batchOk : position(current, "singleOk"))], { resumeGuard: title });
}

async function verifyUpgrade(count, batch) {
    const initial = await snapshot(["selected19"]);
    await guard("selected19", initial);
    await transition("nível 19 após o pagamento", ["wallTitle", "selected19"], current => all(current, ["wallTitle", "selected19"]),
        current => [tapStep(position(current, batch ? "batchDismiss" : "wallDismiss"))]);
    await log(`Melhoria confirmada: ${count} muro(s).`);
}

async function growAndPayBatch(options, resource, unitCost, initial) {
    if (!await act(initial, [tapStep(position(initial, "more"))])) return { retry: true, upgraded: 0 };
    let count = 1;
    let total = unitCost;
    let current = await readBatch(options, resource, total);
    let allowGrow = true;
    await guard("batchRemove", current);
    while (true) {
        if (!availability(current, resource, true)) {
            allowGrow = false;
            if (count === 1) {
                await transition("sair do lote sem saldo", ["batchRemove", "selected18"], value => all(value, ["batchRemove", "selected18"]),
                    value => [tapStep(position(value, "batchDismiss"))]);
                return { upgraded: 0 };
            }
            if (!await act(current, [tapStep(position(current, "remove"), 65, 250)])) {
                current = await readBatch(options, resource, total);
                continue;
            }
            count--; total -= unitCost;
            current = await readBatch(options, resource, total);
            // The remaining total is checked again before opening a payment dialog.
            continue;
        }
        const balance = number(current.results[balanceName(resource)]);
        const canGrow = allowGrow && count < options.maxWallBatch && found(current.results.batchAdd) &&
            canReserve(balance, total + unitCost, reserveFor(options, resource));
        if (!canGrow) break;
        if (!await act(current, [tapStep(position(current, "more"), 65, 250)])) {
            current = await readBatch(options, resource, total);
            continue;
        }
        const enlarged = await readBatch(options, resource, total + unitCost, total);
        if (equalCosts(enlarged, batchCosts, total)) { current = enlarged; break; }
        count++; total += unitCost; current = enlarged;
    }
    await transition(`abrir pagamento do lote em ${resource}`, ["batchRemove", "selected18", ...batchCosts,
        ...priceNames(resource, true), ...(reserveFor(options, resource) > 0 ? [balanceName(resource)] : [])], value =>
        all(value, ["batchRemove", "selected18"]) && equalCosts(value, batchCosts, total) && availability(value, resource, true) === true &&
        canReserve(number(value.results[balanceName(resource)]), total, reserveFor(options, resource)),
    value => [tapStep(position(value, `${resource}Pay`))]);
    await confirmPayment(resource, total, true);
    await verifyUpgrade(count, true);
    return { upgraded: count };
}

export async function improveWalls(options) {
    let upgraded = 0;
    let wallSelected = false;
    const order = options.resourcePriority === "elixir" ? ["elixir", "gold"] : resources;
    while (true) {
        if (!wallSelected) {
            await checkBuilder();
            if (!await selectWall()) return upgraded;
            wallSelected = true;
        }
        const current = await inspectSelected(options);
        const unitCost = number(current.results.goldCost);
        const resource = order.find(resource => availability(current, resource) === true &&
            canReserve(number(current.results[balanceName(resource)]), unitCost, reserveFor(options, resource)));
        if (!resource) {
            await transition("preservar reservas e voltar ao farm", selected, value => all(value, selected),
                value => [tapStep(position(value, "wallDismiss"))]);
            await log("Muros encerrados nesta visita: os dois recursos estão insuficientes ou reservados.");
            return upgraded;
        }
        if (found(current.results.more)) {
            const result = await growAndPayBatch(options, resource, unitCost, current);
            if (result.retry) continue;
            upgraded += result.upgraded;
            if (result.upgraded === 0) return upgraded;
        } else {
            if (!await act(current, [tapStep(position(current, `${resource}Pay`))])) continue;
            await confirmPayment(resource, unitCost, false);
            await verifyUpgrade(1, false);
            upgraded++;
        }
        if (options.goldReserve > 0 || options.elixirReserve > 0) await sleep(500);
        wallSelected = false;
        await waitSnapshot("vila após a melhoria", ["village", "villageText"], value =>
            found(value.results.village) || found(value.results.villageText));
    }
}
