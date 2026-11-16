import { observe, profile, readText, tap, swipe, sleep, now, resumeWhen, log } from "klickr";
import { requiredResources } from "./policy.js";
import { found, waitSnapshot, act, tapStep, position } from "./screen.js";

const settingsTitles = new Set(["configuracoes", "configuraces", "configurares"]);
const normalized = text => text.normalize("NFD").replace(/[\u0300-\u036f\s.:]/g, "").toLowerCase();

// Selector labels locate an item; only a subsequently confirmed account tag identifies a village.
export function selectorRows(blocks, clipTop = 0) {
    const centers = blocks.filter(block => /^centrodavila\d+$/.test(normalized(block.text)));
    return centers.map(center => {
        const height = center.bottom - center.top;
        const candidates = blocks.filter(block => Math.abs(block.left - center.left) < height &&
            block.bottom < center.top && center.top - block.top < height * 3.5);
        if (candidates.length === 0 && center.top - clipTop < height * 3.5) return null;
        if (candidates.length !== 1) throw new Error(`Item de conta ambíguo acima de ${center.text}; esperado um nome completo.`);
        return { key: candidates[0].text, ...candidates[0] };
    }).filter(Boolean);
}

export function stableTag(blocks) {
    const tags = blocks.filter(block => /^#[A-Z0-9]{6,16}$/.test(block.text) && block.confidence >= 95);
    if (tags.length !== 1) throw new Error("Identidade da conta: esperada uma tag completa e inequívoca, com confiança de pelo menos 95%.");
    return tags[0].text;
}

export function capacityValue(blocks, number) {
    const labels = blocks.filter(block => normalized(block.text).startsWith("max"));
    if (labels.length !== 1) throw new Error("Capacidade: esperado um único rótulo Máx. completo e válido.");
    // The label overlaps the underlying balance. Never parse digits attached to it: the real crop read 231M for 31M.
    if (!Number.isSafeInteger(number) || number <= 0 || number > 1e12) throw new Error(`Capacidade=${number}; esperado inteiro positivo até 1000000000000.`);
    return number;
}

export function capacityNumber(blocks) {
    const confident = blocks.filter(block => block.confidence >= 85);
    if (confident.length !== 1 || !/^\d+(?:[.,:\s-]\d+)*$/.test(confident[0].text)) {
        throw new Error("Capacidade: esperado um único inteiro com dígitos e separadores de milhares, confiança mínima de 85%.");
    }
    // This game field is an integer. OCR can merge thousands groups or turn their dots into colons.
    const digits = confident[0].text.replace(/[.,:\s-]/g, "");
    // This profile is calibrated for at most eight digits. Never trim an overlapping background digit.
    if (digits.length > 8) throw new Error("Capacidade fora do perfil: esperados até oito dígitos; recalibre a região numérica.");
    return Number(digits);
}

/** Read only concordant values fitting the calibrated numeric field.
 * Example: await readCapacity({frame, geometry, blocks}, "gold"). */
export async function readCapacity(current, resource) {
    const region = current.geometry.regions[`${resource}CapacityNumber`];
    // The full tooltip can merge Máx. with the balance behind it and discard the label.
    // Confirm the label in its own calibrated crop, independently of the amount.
    let labels = current.blocks;
    if (labels.filter(block => normalized(block.text).startsWith("max")).length !== 1) {
        const region = current.geometry.regions[`${resource}CapacityLabel`];
        const candidates = [];
        for (const dy of [-4, 0, 2]) {
            const label = await readText(current.frame, { region: { ...region, top: region.top + dy }, threshold: 60 });
            if (label.truncated) throw new Error(`Capacidade de ${resource}: rótulo Máx. ambíguo.`);
            const matches = label.blocks.filter(block => normalized(block.text).startsWith("max"));
            if (matches.length !== 1) continue;
            candidates.push(matches[0]);
            if (matches[0].confidence >= 85) { labels = matches; break; }
        }
        // Translucent tooltips can reduce confidence in the short label (observed as Max$ at 61.8%).
        // Require two different label crops in that case; amounts still require >=85%, crop majority
        // and two separate observations. Never extract the amount from the label/background blend.
        if (labels === current.blocks) {
            if (candidates.length < 2) throw new Error(`Capacidade de ${resource}: rótulo Máx. sem confirmação em dois recortes.`);
            labels = [candidates[0]];
        }
    }
    const values = [];
    // A high-confidence background digit can survive a single crop. Require a strict
    // majority across different crops, excluding values outside this eight-digit profile.
    for (const [dx, dy] of [[0,-4], [3,-4], [0,-2], [0,0], [3,0], [0,1]]) {
        const result = await readText(current.frame, { region: { ...region, left: region.left + dx, top: region.top + dy }, threshold: 85 });
        if (result.truncated) throw new Error(`Capacidade de ${resource} excedeu o limite de blocos OCR.`);
        try { values.push(capacityValue(labels, capacityNumber(result.blocks))); }
        catch (error) { if (!error.message.startsWith("Capacidade")) throw error; }
    }
    const votes = new Map();
    for (const value of values) votes.set(value, (votes.get(value) || 0) + 1);
    const ranked = [...votes].sort((a, b) => b[1] - a[1]);
    if (!ranked.length || ranked[0][1] < 2 || ranked[0][1] <= values.length / 2) {
        throw new Error(`Capacidade de ${resource}: leituras ${JSON.stringify(values)}; esperada maioria de ao menos dois recortes válidos.`);
    }
    return ranked[0][0];
}

async function textSnapshot(regionName, threshold = 85) {
    const frame = await observe();
    const geometry = await profile(frame);
    const result = await readText(frame, { region: geometry.regions[regionName], threshold });
    if (result.truncated) throw new Error(`OCR de ${regionName} excedeu 64 blocos; reduza a região antes de continuar.`);
    return { frame, geometry, blocks: result.blocks };
}

async function guardScreen(name, geometry) {
    await resumeWhen({ kind: "text", reference: name, region: geometry.regions[name], threshold: 85 });
}

async function waitText(region, accept, timeoutMs = 10000, required = true) {
    const end = await now() + timeoutMs;
    while (await now() < end) {
        try {
            const current = await textSnapshot(region);
            if (accept(current.blocks)) return current;
        } catch (error) { if (error.code !== "stale_observation") throw error; }
        await sleep(150);
    }
    if (!required) return null;
    throw new Error(`Sem confirmação de ${region} em ${timeoutMs} ms; a rodada foi preservada.`);
}

async function tapNamed(current, name) {
    await tap(current.geometry.positions[name], 150);
}

const homeNames = ["village", "villageText", "accountSettingsTitle", "accountSelectorTitle"];
const isHome = value => (found(value.results.village) || found(value.results.villageText)) &&
    !found(value.results.accountSettingsTitle) && !found(value.results.accountSelectorTitle);

export function hasKnownPopup(blocks) {
    // The real reward title is two boxes: “RecoMPeNsa” (79.6%) and “Recebida” (99.7%).
    const titles = ["itensentregues", "recompensarecebida"];
    return blocks.some(first => {
        const line = blocks.filter(other => Math.abs(other.top - first.top) <= Math.min(
            other.bottom - other.top, first.bottom - first.top) / 2).sort((a, b) => a.left - b.left);
        return titles.includes(normalized(line.map(block => block.text).join("")));
    });
}

async function dismissKnownPopup() {
    const popup = await textSnapshot("accountPopup", 70);
    const known = hasKnownPopup(popup.blocks);
    if (!known) return false;
    const buttons = popup.blocks.filter(block => block.confidence >= 90 && ["ok", "continuar", "coletar"].includes(normalized(block.text)));
    if (buttons.length !== 1) throw new Error("Aviso conhecido sem botão inequívoco; feche-o e continue a rodada.");
    const button = buttons[0];
    await tap({ x: Math.round((button.left + button.right) / 2), y: Math.round((button.top + button.bottom) / 2), frame: popup.frame.frame }, 150);
    await sleep(300);
    return true;
}

export async function confirmVillage() {
    const end = await now() + 45000;
    while (await now() < end) {
        // Delivery dialogs may arrive after the first stable home frame following an account switch.
        if (await dismissKnownPopup()) continue;
        const current = await waitSnapshot("vila utilizável", homeNames, isHome,
            { timeoutMs: 1500, intervalMs: 200, required: false });
        if (current) return current;
    }
    throw new Error("Vila utilizável não confirmada em 45 segundos; rodada preservada.");
}

async function openSettings() {
    const visible = await textSnapshot("accountSettingsTitle");
    let tabs = visible.blocks.some(block => settingsTitles.has(normalized(block.text))) ? visible : null;
    for (let attempt = 0; !tabs && attempt < 3; attempt++) {
        const village = await confirmVillage();
        if (!await act(village, [tapStep(position(village, "accountSettingsOpen"), 180)])) continue;
        tabs = await waitText("accountSettingsTitle", blocks => blocks.some(block => settingsTitles.has(normalized(block.text))), 3000, false);
        if (tabs) break;
        // Android can display the village before the game accepts input after changing apps.
        // Retry only after recognizing the village again, never over an unrecognized modal.
    }
    if (!tabs) throw new Error("O perfil não abriu após três tentativas confirmadas na vila.");
    const settings = tabs.blocks.find(block => settingsTitles.has(normalized(block.text)));
    await tap({ x: Math.round((settings.left + settings.right) / 2), y: Math.round((settings.top + settings.bottom) / 2), frame: tabs.frame.frame }, 150);
    const ready = await waitText("accountIdentity", blocks => { try { stableTag(blocks); return true; } catch (_) { return false; } });
    await guardScreen("accountSettingsTitle", ready.geometry);
    return ready;
}

export async function currentIdentity({ close = true } = {}) {
    const first = await openSettings();
    const tag = stableTag(first.blocks);
    await sleep(200);
    const confirmed = await textSnapshot("accountIdentity");
    if (stableTag(confirmed.blocks) !== tag) throw new Error("A tag mudou entre duas leituras; identidade não confirmada.");
    if (close) {
        let closed = false;
        for (let attempt = 0; attempt < 3; attempt++) {
            const current = await textSnapshot("accountSettingsTitle");
            if (!current.blocks.some(block => settingsTitles.has(normalized(block.text)))) break;
            await tapNamed(current, "accountSettingsClose");
            const home = await waitSnapshot("fechamento das configurações", homeNames, isHome,
                { timeoutMs: 1500, intervalMs: 150, required: false });
            if (home) { closed = true; break; }
        }
        if (!closed) throw new Error("Configurações não fecharam após três tentativas; nenhum ataque autorizado.");
    }
    return tag;
}

async function openSelector() {
    const settings = await openSettings();
    await tapNamed(settings, "accountSelectorOpen");
    const title = await waitText("accountSelectorTitle", blocks => blocks.some(block => normalized(block.text) === "trocardeid"));
    await guardScreen("accountSelectorTitle", title.geometry);
    await rewindSelector();
}

async function selectorPage() {
    return waitText("accountList", blocks => selectorRows(blocks, 200).length > 0 || blocks.some(block => normalized(block.text).includes("facalogin")));
}

async function rewindSelector() {
    let previous = "";
    let unchanged = 0;
    for (let attempt = 0; attempt < 12; attempt++) {
        const current = await selectorPage();
        const signature = selectorRows(current.blocks, current.geometry.regions.accountList.top)
            .map(row => `${row.key}:${row.top}`).join("|");
        unchanged = signature === previous ? unchanged + 1 : 0;
        if (unchanged >= 2) return;
        previous = signature;
        await swipe(current.geometry.positions.accountScrollTo, current.geometry.positions.accountScrollFrom, 350);
        await sleep(300);
    }
    throw new Error("Não foi possível confirmar o início da lista em 12 tentativas.");
}

async function scrollPage(current) {
    await swipe(current.geometry.positions.accountScrollFrom, current.geometry.positions.accountScrollTo, 350);
    await sleep(350);
}

// Always reopen and find by the complete selector label. Stored row indexes never authorize a tap.
async function locateAccount(key, occurrencePage) {
    await openSelector();
    if (occurrencePage !== undefined) {
        for (let page = 0; page < occurrencePage; page++) await scrollPage(await selectorPage());
        const current = await selectorPage();
        const rows = selectorRows(current.blocks, current.geometry.regions.accountList.top).filter(row => row.key === key);
        if (rows.length !== 1) throw new Error(`Conta ${key}: a lista mudou durante a descoberta.`);
        return { current, row: rows[0] };
    }
    for (let page = 0; page < 12; page++) {
        const current = await selectorPage();
        const rows = selectorRows(current.blocks, current.geometry.regions.accountList.top).filter(row => row.key === key);
        if (rows.length > 1) throw new Error(`Conta ${key}: nome duplicado no seletor; seleção ambígua.`);
        if (rows.length === 1) return { current, row: rows[0] };
        if (current.blocks.some(block => normalized(block.text).includes("facalogin"))) break;
        await scrollPage(current);
    }
    throw new Error(`Conta ${key} removida, desconectada ou não encontrada em 12 páginas; rodada interrompida.`);
}

async function waitLoadedVillage() {
    const end = await now() + 60000;
    while (await now() < end) {
        if (await dismissKnownPopup()) {
            continue;
        } else {
            const village = await waitSnapshot("carregamento da vila", homeNames, isHome, { timeoutMs: 1500, intervalMs: 150, required: false });
            if (village) {
                await sleep(300);
                const confirmed = await waitSnapshot("vila carregada estável", homeNames, isHome,
                    { timeoutMs: 1500, intervalMs: 150, required: false });
                if (confirmed) return;
            }
        }
        await sleep(300);
    }
    throw new Error("A troca de conta não terminou em 60 segundos; confira os avisos e continue a rodada.");
}

async function selectAccount(key, occurrencePage) {
    const { current, row } = await locateAccount(key, occurrencePage);
    await tap({ x: Math.round((row.left + row.right) / 2), y: Math.round((row.top + row.bottom) / 2), frame: current.frame.frame }, 150);
    await waitLoadedVillage();
    return currentIdentity();
}

export async function switchVillage(village) {
    const tag = await selectAccount(village.key);
    if (tag !== village.id) throw new Error(`Conta ${village.key}: tag ${tag}, esperada ${village.id}; nenhum ataque autorizado.`);
    return tag;
}

export async function discoverVillages(initial, onVillage = async () => ({})) {
    if (!initial) initial = await currentIdentity();
    await openSelector();
    const occurrences = [];
    let previous = [];
    let finished = false;
    for (let page = 0; page < 12; page++) {
        const current = await selectorPage();
        const names = selectorRows(current.blocks, current.geometry.regions.accountList.top).map(row => row.key);
        if (new Set(names).size !== names.length) throw new Error("Nomes duplicados no seletor; descoberta ambígua.");
        // Visit each occurrence, including scroll overlap, before deduplicating by confirmed tag.
        for (const key of names) occurrences.push({ key, page });
        if (occurrences.length > 192) throw new Error("Lista excedeu 192 itens observados; descoberta interrompida.");
        if (current.blocks.some(block => normalized(block.text).includes("facalogin"))) { finished = true; break; }
        if (page > 0 && names.join("|") === previous.join("|")) throw new Error("A lista não avançou; fim da descoberta não confirmado.");
        previous = names;
        await scrollPage(current);
    }
    if (!finished || occurrences.length === 0) throw new Error("Lista de contas incompleta; descoberta não concluída.");
    const close = await textSnapshot("accountSelectorTitle");
    await tapNamed(close, "accountSelectorClose");
    // Closing the ID selector returns to settings, not necessarily the village.
    await waitText("accountSettingsTitle", blocks => blocks.some(block => settingsTitles.has(normalized(block.text))));
    await currentIdentity();
    const villages = [];
    for (const { key, page } of occurrences) {
        const id = await selectAccount(key, page);
        const sameName = villages.find(village => village.key === key);
        if (sameName && sameName.id !== id) throw new Error(`Nome ${key} associado a tags diferentes; seleção ambígua.`);
        const sameTag = villages.find(village => village.id === id);
        if (sameTag && sameTag.key !== key) throw new Error(`Tag ${id} associada a nomes distintos; identidade ambígua.`);
        if (sameTag) continue;
        if (villages.length >= 64) throw new Error("Mais de 64 vilas; descoberta interrompida.");
        const village = { id, key, name: key, label: key };
        village.capacities = await onVillage(village);
        villages.push(village);
    }
    const starting = villages.find(village => village.id === initial);
    if (!starting) throw new Error("A conta inicial não foi encontrada na lista conectada.");
    await switchVillage(starting);
    return [starting, ...villages.filter(village => village.id !== initial)];
}

export async function villageCapacities(options = null) {
    const resources = options ? requiredResources(options.stopFarm) : ["gold", "elixir", "dark"];
    const capacities = {};
    for (const resource of resources) {
        const village = await confirmVillage();
        if (!await act(village, [tapStep(position(village, `${resource}CapacityOpen`), 150)])) throw new Error("Captura invalidada antes da leitura da capacidade; continue a rodada.");
        const end = await now() + 10000;
        let previous;
        let confirmed;
        let last;
        let lastFailure;
        while (await now() < end) {
            const current = await textSnapshot(`${resource}Capacity`);
            try {
                const amount = await readCapacity(current, resource);
                if (amount === previous) { confirmed = amount; last = current; break; }
                previous = amount;
            } catch (error) {
                if (error.code !== "stale_observation" && !error.message.startsWith("Capacidade")) throw error;
                lastFailure = error.message;
                previous = undefined;
            }
            // The tooltip label can appear before its numeric glyphs finish animating.
            await sleep(200);
        }
        if (confirmed === undefined) {
            const reason = `Capacidade de ${resource} sem duas leituras válidas e concordantes em 10 segundos; cadastro ou rodada anterior preservados. ${lastFailure || `Última leitura: ${previous ?? "nenhuma"}.`}`;
            await log(reason);
            throw new Error(reason);
        }
        capacities[`${resource}Capacity`] = confirmed;
        await tapNamed(last, "accountTooltipClose");
        await confirmVillage();
    }
    return capacities;
}
