export const apiVersion = 1;

async function call(operation, fields = {}) {
    const result = JSON.parse(await __klickrCall(JSON.stringify({ ...fields, apiVersion, operation })));
    if (result.status === "error") {
        const error = new Error(result.message);
        error.code = result.code;
        throw error;
    }
    return result;
}

export async function observe() {
    return Object.freeze(await call("observe"));
}

export async function read(observation, query) {
    return Object.freeze(await call("read", { ...query, frame: observation.frame }));
}

export async function readAll(observation, queries) {
    const result = await call("readAll", { queries, frame: observation.frame });
    return Object.freeze(result.results.map(Object.freeze));
}

export async function profile(observation) {
    const resolved = await call("profile", { frame: observation.frame });
    for (const region of Object.values(resolved.regions)) Object.freeze(region);
    for (const point of Object.values(resolved.positions)) {
        point.frame = observation.frame;
        Object.freeze(point);
    }
    Object.freeze(resolved.regions);
    Object.freeze(resolved.positions);
    return Object.freeze(resolved);
}

export async function tap(position, durationMs = 50) {
    return call("tap", { x: position.x, y: position.y, frame: position.frame, durationMs });
}

export async function tapSequence(steps, observation) {
    return call("tapSequence", { steps, frame: observation.frame });
}

export async function swipe(from, to, durationMs = 300) {
    return call("swipe", { from, to, frame: from.frame, durationMs });
}

export async function zoom(center, direction, intensityPx = 150, durationMs = 300) {
    return call("zoom", { center, direction, intensityPx, durationMs, frame: center.frame });
}

export async function gesture(fingers, observation) {
    return call("gesture", { fingers, frame: observation?.frame });
}

export async function sleep(durationMs) {
    return call("sleep", { durationMs });
}

export async function parameters() {
    return Object.freeze((await call("parameters")).values);
}

export async function stage(name, message = "") {
    return call("stage", { name, message });
}

export async function log(message) {
    return call("log", { message });
}

export async function stop(message) {
    return call("stop", { message });
}

export async function resumeWhen(query) {
    return call("resumeWhen", { query });
}

async function nowMs() {
    return (await call("now")).activeNanos / 1000000;
}

export { nowMs as now };

function positiveInteger(field, value) {
    if (!Number.isSafeInteger(value) || value <= 0) {
        throw new TypeError(`${field}=${value}; expected a positive safe integer`);
    }
}

// Each poll is a host API checkpoint. All alternatives read the same captured frame.
export async function waitAny(alternatives, { timeoutMs, intervalMs = 10 }) {
    positiveInteger("timeoutMs", timeoutMs);
    positiveInteger("intervalMs", intervalMs);
    if (intervalMs > 60000) throw new RangeError(`intervalMs=${intervalMs}; expected 1..60000`);
    if (!Array.isArray(alternatives) || alternatives.length === 0) {
        throw new TypeError("alternatives: expected a nonempty array of named queries");
    }
    const names = new Set();
    for (const alternative of alternatives) {
        if (typeof alternative.name !== "string" || !alternative.name || names.has(alternative.name)) {
            throw new TypeError("alternatives.name: expected unique nonempty names");
        }
        names.add(alternative.name);
    }
    const deadline = await nowMs() + timeoutMs;
    let last = [];
    poll: while (await nowMs() < deadline) {
        const observation = await observe();
        last = [];
        for (const alternative of alternatives) {
            let result;
            try {
                result = await read(observation, alternative.query);
            } catch (error) {
                // A cooperative pause discards the whole poll, including earlier alternatives.
                if (error.code === "stale_observation") continue poll;
                throw error;
            }
            last.push({ name: alternative.name, result });
            const current = await call("now");
            if (current.frame !== observation.frame) continue poll;
            if (current.activeNanos / 1000000 >= deadline) return { status: "timeout", last };
            if (result.status === "found") return { status: "found", name: alternative.name, result };
        }
        const remaining = deadline - await nowMs();
        if (remaining <= 0) break;
        await sleep(Math.max(1, Math.min(intervalMs, Math.ceil(remaining))));
    }
    return { status: "timeout", last };
}

export async function waitFor(query, options) {
    const outcome = await waitAny([{ name: "condition", query }], options);
    return outcome.status === "found" ? outcome.result : outcome;
}

export async function retry(operation, { attempts, intervalMs = 10 }) {
    positiveInteger("attempts", attempts);
    positiveInteger("intervalMs", intervalMs);
    if (intervalMs > 60000) throw new RangeError(`intervalMs=${intervalMs}; expected 1..60000`);
    let result;
    for (let attempt = 1; attempt <= attempts; attempt++) {
        result = await operation(attempt);
        if (result?.status === "found" || result?.status === "success") return result;
        if (attempt < attempts) await sleep(intervalMs);
    }
    return result;
}
