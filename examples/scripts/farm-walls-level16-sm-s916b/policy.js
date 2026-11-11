export function validateOptions(options) {
    for (const name of ["targetDestruction", "maxWallBatch", "maxBattles", "goldReserve", "elixirReserve",
        "goldMinimum", "elixirMinimum", "goldCapacity", "elixirCapacity"]) {
        if (!Number.isSafeInteger(options[name])) throw new Error(`${name}=${options[name]}; esperado um inteiro seguro.`);
    }
    if (options.targetDestruction < 1 || options.targetDestruction > 100) throw new Error("Meta de destruição: esperado 1 a 100.");
    if (options.maxWallBatch < 1 || options.maxWallBatch > 50) throw new Error("Lote de muros: esperado 1 a 50.");
    if (options.goldReserve < 0 || options.elixirReserve < 0 || options.goldMinimum < 0 || options.elixirMinimum < 0 || options.maxBattles < 0) {
        throw new Error("Reservas, limites de saldo e batalhas: esperados valores não negativos.");
    }
    if (options.goldCapacity <= 0 || options.elixirCapacity <= 0) throw new Error("Capacidades: esperados valores positivos.");
}

export function resourceRule(rule, balances, options) {
    if (rule === "nunca") return false;
    if (rule === "saldo disponível") return true;
    const gold = Number.isFinite(balances.gold) ? balances.gold >= options.goldCapacity : undefined;
    const elixir = Number.isFinite(balances.elixir) ? balances.elixir >= options.elixirCapacity : undefined;
    if (rule === "ouro cheio") return gold;
    if (rule === "elixir cheio") return elixir;
    if (rule === "ambos cheios") return gold === false || elixir === false ? false : gold === true && elixir === true ? true : undefined;
    if (rule === "qualquer depósito cheio") return gold === true || elixir === true ? true : gold === false && elixir === false ? false : undefined;
    throw new Error(`Regra de recursos desconhecida: ${rule}.`);
}

export function battleFinished(options, destruction, goldLoot, elixirLoot) {
    if (!Number.isFinite(destruction)) return false;
    if (options.finishMode === "destruição") return destruction >= options.targetDestruction;
    if (options.finishMode === "saque total") return goldLoot === 0 && elixirLoot === 0;
    if (options.finishMode === "fim natural") return false;
    throw new Error(`Modo de encerramento desconhecido: ${options.finishMode}.`);
}

export function nextBurst(remaining, attempted) {
    if (!Number.isSafeInteger(remaining) || remaining <= 0) throw new Error(`Quantidade de tropas inválida: ${remaining}.`);
    const available = 600 - attempted;
    if (available <= 0) return 0;
    return [16, 8, 4, 2, 1].find(count => count <= remaining && count <= available);
}

export function canReserve(balance, total, reserve) {
    return reserve === 0 || Number.isFinite(balance) && balance >= total + reserve;
}
