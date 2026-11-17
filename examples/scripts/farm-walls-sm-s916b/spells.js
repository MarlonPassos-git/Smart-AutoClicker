import { readText, read } from "klickr";

/** Parse a card counter, including zero; e.g. spellQuantity("x11") === 11. Unreadable text stays undefined. */
export function spellQuantity(text) {
    let digits = text.replace(/\s/g, "");
    if (/^[xX×]/.test(digits)) digits = digits.slice(1).replace(/[Il]/g, "1").replace(/[Oo]/g, "0");
    if (!/^\d+$/.test(digits)) return undefined;
    const value = Number(digits);
    return Number.isSafeInteger(value) ? value : undefined;
}

/** Read counters to the right of the observed heroes without identifying spell portraits.
 * Example: await readSpellCards(current, lastHeroRight / current.frame.width). */
export async function readSpellCards(current, leftRatio, knownSlots = []) {
    if (!Number.isFinite(leftRatio) || leftRatio <= 0 || leftRatio >= 1) {
        throw new Error(`Limite dos feitiços=${leftRatio}; esperado limite observado à direita dos heróis, entre 0 e 1.`);
    }
    const scale = current.frame.width / 2340;
    // Bottle artwork can suppress an x1 text box in the wider troop strip; keep only the card headers.
    const row = { ...current.calibration.regions.troops, bottom: current.calibration.regions.troops.bottom - Math.round(10 * scale) };
    const left = Math.max(row.left, Math.ceil(leftRatio * current.frame.width));
    const coreWidth = Math.round(60 * scale);
    const context = Math.round(40 * scale);
    const cards = [];
    const conflicts = new Map();
    // Small OCR windows preserve narrow xN glyphs; each core owns its labels, including at a window edge.
    for (let core = left; core < row.right; core += coreWidth) {
        const end = Math.min(row.right, core + coreWidth);
        const region = { ...row, left: Math.max(left, core - context), right: Math.min(row.right, end + context) };
        const result = await readText(current.frame, { region, threshold: 80 });
        if (result.truncated) throw new Error("Barra de feitiços excedeu o limite de blocos OCR; nenhum lançamento autorizado.");
        for (const block of result.blocks) {
            const x = Math.round((block.left + block.right) / 2);
            const quantity = /^[xX×]/.test(block.text.trim()) ? spellQuantity(block.text) : undefined;
            if (x < core || x >= end || quantity === undefined) continue;
            const duplicate = cards.find(card => Math.abs(card.at.x - x) <= 40 * scale);
            if (duplicate) {
                if (duplicate.quantity !== quantity) conflicts.set(duplicate, [duplicate.quantity, quantity]);
                continue;
            }
            cards.push({ quantity, xRatio: x / current.frame.width,
                at: { x, y: Math.round((block.top + block.bottom) / 2), frame: current.frame.frame } });
        }
    }
    // OCR can confidently collapse x11 to x1 or omit it. Confirm this ambiguous glyph throughout
    // deployment; the reference contains only the counter, never the spell portrait.
    {
        const eleven = await read(current.frame, { kind: "image", reference: "spellEleven",
            region: { ...current.calibration.regions.troops, left }, threshold: 12 });
        if (eleven.status === "found") {
            const duplicate = cards.find(card => Math.abs(card.at.x - eleven.x) <= 60 * scale);
            if (duplicate) {
                duplicate.quantity = 11;
                duplicate.xRatio = eleven.x / current.frame.width;
                duplicate.at = { x: eleven.x, y: eleven.y, frame: current.frame.frame };
                conflicts.delete(duplicate);
            }
            else cards.push({ quantity: 11, xRatio: eleven.x / current.frame.width,
                at: { x: eleven.x, y: eleven.y, frame: current.frame.frame } });
        }
    }
    // Overlapping OCR crops can read the same x7 as both x71 and x7. Require
    // agreement from two focused crops; never choose the smaller value by guesswork.
    for (const [card, quantities] of conflicts) {
        const known = knownSlots.find(slot => Math.abs(card.xRatio - slot.xRatio) <= 60 / 2340);
        const center = (known?.xRatio ?? card.xRatio) * current.frame.width;
        const readings = [];
        for (const width of [100, 60, 110]) {
            const region = { ...row, left: Math.max(left, Math.floor(center - width / 2 * scale)),
                right: Math.min(row.right, Math.ceil(center + width / 2 * scale)) };
            const text = await readText(current.frame, { region, threshold: 80 });
            if (text.truncated) continue;
            const candidates = text.blocks.map(block => ({ block, quantity: spellQuantity(block.text) }))
                .filter(value => /^[xX×]/.test(value.block.text.trim()) && value.quantity !== undefined &&
                    Math.abs((value.block.left + value.block.right) / 2 - center) <= 40 * scale &&
                    (!known || value.quantity <= known.quantity));
            if (candidates.length === 1) readings.push(candidates[0]);
        }
        const agreed = readings.find(value => readings.filter(other => other.quantity === value.quantity).length >= 2);
        if (!agreed) {
            const error = new Error(`Contador de feitiço divergente: ${quantities.join(" e ")}; aguardando nova leitura.`);
            error.code = "conflicting_spell_counter";
            throw error;
        }
        card.quantity = agreed.quantity;
        card.at = { x: Math.round((agreed.block.left + agreed.block.right) / 2),
            y: Math.round((agreed.block.top + agreed.block.bottom) / 2), frame: current.frame.frame };
        card.xRatio = card.at.x / current.frame.width;
    }
    // Re-read a missing known header with its own window: selected borders change OCR segmentation.
    // A missing positive reading alone never means zero. The x1/x0 glyphs have no spell portrait.
    for (const slot of knownSlots) {
        if (cards.some(card => Math.abs(card.xRatio - slot.xRatio) <= 60 / 2340)) continue;
        let recovered = false;
        for (const width of [100, 60, 110]) {
            const region = { ...row,
                left: Math.max(left, Math.floor(slot.xRatio * current.frame.width - width / 2 * scale)),
                right: Math.min(row.right, Math.ceil(slot.xRatio * current.frame.width + width / 2 * scale)) };
            const text = await readText(current.frame, { region, threshold: 80 });
            if (text.truncated) throw new Error("Contador de feitiço excedeu o limite OCR; nenhum lançamento autorizado.");
            const quantities = text.blocks.map(block => ({ block, quantity: spellQuantity(block.text) }))
                .filter(value => /^[xX×]/.test(value.block.text.trim()) && value.quantity !== undefined);
            if (quantities.length !== 1) continue;
            const { block, quantity } = quantities[0];
            cards.push({ quantity, xRatio: slot.xRatio,
                at: { x: Math.round((block.left + block.right) / 2), y: Math.round((block.top + block.bottom) / 2), frame: current.frame.frame } });
            recovered = true;
            break;
        }
        if (recovered) continue;
        const region = { ...current.calibration.regions.troops,
            left: Math.max(left, Math.floor(slot.xRatio * current.frame.width - 60 * scale)),
            right: Math.min(row.right, Math.ceil(slot.xRatio * current.frame.width + 60 * scale)) };
        for (const { reference, quantity } of [{ reference: "spellOne", quantity: 1 },
            { reference: "spellZeroCv19", quantity: 0 }, { reference: "spellZero", quantity: 0 }]) {
            // With blocks of two, a known count above three cannot already be one or zero.
            if (Number.isFinite(slot.quantity) && slot.quantity > quantity + 2) continue;
            const counter = await read(current.frame, { kind: "image", reference,
                region, threshold: 12 });
            if (counter.status !== "found") continue;
            cards.push({ quantity, xRatio: slot.xRatio, at: { x: counter.x, y: counter.y, frame: current.frame.frame } });
            break;
        }
    }
    return cards.sort((a, b) => a.at.x - b.at.x);
}
