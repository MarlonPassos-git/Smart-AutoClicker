export function validateOptions(options, requireCapacities = false) {
    if (options.multivillage && options.improveWalls) throw new Error("Multivilas exige Melhorar muros desativado.");
    if (options.multivillage && options.maxBattles === 0 && options.stopFarm === "nunca" && options.goldMinimum === 0 && options.elixirMinimum === 0) {
        throw new Error("Multivilas exige limite de ataques ou condição de parada por recursos.");
    }
    for (const name of ["targetDestruction", "maxWallBatch", "maxBattles", "goldReserve", "elixirReserve",
        "goldMinimum", "elixirMinimum"]) {
        if (!Number.isSafeInteger(options[name])) throw new Error(`${name}=${options[name]}; esperado um inteiro seguro.`);
    }
    if (options.targetDestruction < 1 || options.targetDestruction > 100) throw new Error("Meta de destruição: esperado 1 a 100.");
    if (options.maxWallBatch < 1 || options.maxWallBatch > 50) throw new Error("Lote de muros: esperado 1 a 50.");
    if (options.goldReserve < 0 || options.elixirReserve < 0 || options.goldMinimum < 0 || options.elixirMinimum < 0 || options.maxBattles < 0) {
        throw new Error("Reservas, limites de saldo e batalhas: esperados valores não negativos.");
    }
    if (requireCapacities) {
        const needed = new Set([...requiredResources(options.stopFarm), ...requiredResources(options.improveWalls ? options.wallTrigger : "nunca")]);
        for (const resource of needed) {
            const capacity = options[`${resource}Capacity`];
            if (!Number.isSafeInteger(capacity) || capacity <= 0) throw new Error(`Capacidade de ${resource}=${capacity}; execute Configurar vilas para ler um inteiro positivo.`);
        }
    }
}

/** Resources required by a full-storage rule; e.g. requiredResources("elixir negro cheio"). */
export function requiredResources(rule) {
    if (rule === "ouro cheio") return ["gold"];
    if (rule === "elixir cheio") return ["elixir"];
    if (rule === "elixir negro cheio") return ["dark"];
    if (rule === "ambos cheios" || rule === "qualquer depósito de ouro ou elixir cheio") return ["gold", "elixir"];
    if (rule === "qualquer depósito cheio" || rule === "todos cheios") return ["gold", "elixir", "dark"];
    if (rule === "nunca" || rule === "saldo disponível") return [];
    throw new Error(`Regra de recursos desconhecida: ${rule}.`);
}

export function resourceRule(rule, balances, options) {
    if (rule === "nunca") return false;
    if (rule === "saldo disponível") return true;
    const full = requiredResources(rule).map(resource => Number.isFinite(balances[resource]) && Number.isFinite(options[`${resource}Capacity`])
        ? balances[resource] >= options[`${resource}Capacity`] : undefined);
    if (rule === "qualquer depósito cheio" || rule === "qualquer depósito de ouro ou elixir cheio") return full.includes(true) ? true : full.includes(undefined) ? undefined : false;
    return full.includes(false) ? false : full.includes(undefined) ? undefined : true;
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
