import { observe, profile, readAll, now, sleep, stage, resumeWhen, tapSequence, stop } from "klickr";
import { queries } from "./profile.js";

export const found = result => result?.status === "found";
export const number = result => found(result) && Number.isFinite(result.number) ? result.number : undefined;
export const all = (snapshot, names) => names.every(name => found(snapshot.results[name]));

let calibrationGeometry;

async function calibrationFor(frame) {
    if (!calibrationGeometry || calibrationGeometry.width !== frame.width || calibrationGeometry.height !== frame.height) {
        const resolved = await profile(frame);
        // A running package has an immutable profile. Retain geometry without retaining an observation's frame.
        calibrationGeometry = { ...resolved, positions: Object.fromEntries(
            Object.entries(resolved.positions).map(([name, point]) => [name, { x: point.x, y: point.y }])) };
    }
    const positions = Object.fromEntries(Object.entries(calibrationGeometry.positions)
        .map(([name, point]) => [name, Object.freeze({ ...point, frame: frame.frame })]));
    return Object.freeze({ ...calibrationGeometry, positions: Object.freeze(positions) });
}

export function query(name, calibration) {
    const definition = queries[name];
    if (!definition) throw new Error(`Consulta não declarada: ${name}.`);
    return { ...definition, region: calibration.regions[definition.region] };
}

// Restart the entire group if a cooperative pause discards its frame before the batch.
export async function snapshot(names, resumeGuard) {
    for (let attempt = 0; attempt < 3; attempt++) {
        try {
            const frame = await observe();
            const calibration = await calibrationFor(frame);
            // Declare the expected screen before a potentially long native batch; resume still verifies it afresh.
            if (resumeGuard) await guard(resumeGuard, { calibration });
            const unique = [...new Set(names)];
            const values = await readAll(frame, unique.map(name => query(name, calibration)));
            const results = Object.fromEntries(unique.map((name, index) => [name, values[index]]));
            return { frame, calibration, results };
        } catch (error) {
            if (error.code !== "stale_observation") throw error;
        }
    }
    await stop("A tela foi invalidada em três observações; inicie novamente na vila.");
}

export async function guard(name, current) {
    await resumeWhen(query(name, current.calibration));
}

export async function waitSnapshot(label, names, predicate, { timeoutMs = 5000, intervalMs = 50, required = true, resumeGuard } = {}) {
    await stage(label, `Aguardando confirmação de ${label}.`);
    const deadline = await now() + timeoutMs;
    do {
        const current = await snapshot(names, resumeGuard);
        if (await now() >= deadline) break;
        if (predicate(current)) {
            const resume = names.find(name => found(current.results[name]));
            if (resume) await guard(resume, current);
            return current;
        }
        const remaining = deadline - await now();
        if (remaining <= 0) break;
        await sleep(Math.max(1, Math.min(intervalMs, Math.ceil(remaining))));
    } while (await now() < deadline);
    if (!required) return null;
    await stop(`Sem confirmação de ${label} em ${timeoutMs} ms; nenhum próximo clique foi autorizado.`);
}

export const position = (current, name) => {
    const point = current.calibration.positions[name];
    if (!point) throw new Error(`Posição não calibrada: ${name}.`);
    return point;
};

export const tapStep = (at, pressMs = 65, afterMs = 0) => ({ at, pressMs, afterMs });

// One frame guards the complete block, which the host limits to one second.
export async function act(current, steps) {
    try {
        await tapSequence(steps, current.frame);
        return true;
    } catch (error) {
        if (error.code === "stale_observation") return false;
        throw error;
    }
}

export async function transition(label, names, predicate, steps, options) {
    for (let attempt = 0; attempt < 3; attempt++) {
        const current = await waitSnapshot(label, names, predicate, options);
        if (await act(current, steps(current))) return current;
    }
    await stop(`A observação mudou três vezes antes de ${label}; inicie novamente na vila.`);
}
