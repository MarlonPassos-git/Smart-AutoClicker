import { loadConfiguration, saveConfiguration, progress, stage } from "klickr";
import { currentIdentity, discoverVillages, villageCapacities } from "./villages.js";

const profileId = "sm-s916b-2340x1080-v1";

/** Reject incomplete or incompatible catalogs before a gesture; e.g. validateConfiguration(await loadConfiguration()). */
export function validateConfiguration(value) {
    if (value?.formatVersion !== 1 || value.profile !== profileId || !Array.isArray(value.items) || value.items.length < 1 || value.items.length > 64) {
        throw new Error("Cadastro de vilas ausente ou incompatível; execute Configurar vilas antes de farmar.");
    }
    const ids = new Set();
    for (const village of value.items) {
        if (!/^#[A-Z0-9]{6,16}$/.test(village.id) || ids.has(village.id) || typeof village.key !== "string" || !village.key.trim() ||
            typeof village.label !== "string" || !village.label.trim() || village.label.length > 256) {
            throw new Error("Cadastro de vilas inválido ou ambíguo; execute Configurar vilas novamente.");
        }
        for (const resource of ["gold", "elixir", "dark"]) {
            const capacity = village.capacities?.[`${resource}Capacity`];
            if (!Number.isSafeInteger(capacity) || capacity <= 0 || capacity > 1e12) {
                throw new Error(`Capacidade de ${resource}=${capacity}; esperado inteiro positivo no cadastro. Execute Configurar vilas.`);
            }
        }
        ids.add(village.id);
    }
    return value;
}

/** Visits accounts without attacking; commits the entire catalog only after returning to the initial village.
 * Example: await configureVillages() from the independent setup entry.
 */
export async function configureVillages(automation = { currentIdentity, discoverVillages, villageCapacities }) {
    await stage("Configurando vilas", "Lendo as contas e as capacidades dos três recursos. Nenhum ataque será iniciado.");
    const initial = await automation.currentIdentity();
    const items = await automation.discoverVillages(initial, async village => {
        await progress(`Configurando ${village.label || village.name}\nLendo ouro, elixir e elixir negro`);
        return automation.villageCapacities();
    });
    if (await automation.currentIdentity() !== initial) throw new Error("A vila inicial não foi restaurada; cadastro anterior preservado.");
    const configuration = validateConfiguration({ formatVersion: 1, profile: profileId, updatedAt: new Date().toISOString(), items });
    await saveConfiguration(configuration);
    await progress(`${items.length} vila(s) configurada(s)\nOuro, elixir e elixir negro salvos`);
    await stage("Vilas configuradas", `${items.length} vila(s) salva(s) neste aparelho. Selecione as vilas em Iniciar para farmar.`);
}

/** Resolves saved tags in user order, rejecting removed accounts; e.g. await configuredVillages(options). */
export async function configuredVillages(options) {
    const configuration = validateConfiguration(await loadConfiguration());
    if (!Array.isArray(options.villages) || !options.villages.length || new Set(options.villages).size !== options.villages.length) {
        throw new Error("Selecione pelo menos uma vila configurada para farmar.");
    }
    return options.villages.map(id => {
        const village = configuration.items.find(item => item.id === id);
        if (!village) throw new Error(`Vila ${id} ausente do cadastro; selecione novamente ou execute Configurar vilas.`);
        return { ...village, name: village.label, attacks: 0, status: "pending" };
    });
}
