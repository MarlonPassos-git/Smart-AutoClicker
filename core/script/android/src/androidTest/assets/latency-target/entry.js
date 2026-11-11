import { observe, readAll, tap, parameters, now, sleep, stage, log, stop, waitFor, resumeWhen } from 'klickr';

const options = await parameters();
await stage('preparando', 'Abra o alvo de teste antes de iniciar. A execução tocará apenas no retângulo colorido.');
if (options.startDelayMs > 0) await sleep(options.startDelayMs);
const initial = await observe();
const x = Math.floor(initial.width / 2);
const y = Math.floor(initial.height * .8);
const signature = { kind: 'color', reference: 'signature', threshold: 0,
    region: { left: 10, top: Math.floor(initial.height * .2), right: 30, bottom: Math.floor(initial.height * .2) + 20 } };
const color = generation => ({ kind: 'color', reference: generation % 2 === 0 ? 'green' : 'blue', threshold: 0,
    region: { left: x - 16, top: y - 16, right: x + 16, bottom: y + 16 } });
const identity = await readAll(initial, [signature, color(0)]);
if (!identity.every(value => value.status === 'found')) {
    await log(JSON.stringify({ kind: 'latency_target_rejected', frame: initial, reads: identity }));
    await stop('O alvo controlado não foi confirmado; nenhum toque foi autorizado.');
}
await resumeWhen(signature);
await stage('medindo', 'Conferindo captura, cor e toque no alvo controlado.');
const rows = [];
for (let iteration = 0; iteration < 23; iteration++) {
    const waitingStarted = await now();
    const ready = await waitFor(color(iteration), { timeoutMs: 1500, intervalMs: 10 });
    if (ready.status !== 'found') await stop('O alvo não confirmou a cor esperada antes da observação.');
    const started = await now();
    const frame = await observe();
    const captured = await now();
    const results = await readAll(frame, [signature, color(iteration)]);
    const recognized = await now();
    if (!results.every(value => value.status === 'found')) await stop('A nova captura não confirmou o alvo; nenhum próximo toque foi autorizado.');
    await tap({ x, y, frame: frame.frame }, 1);
    const completed = await now();
    if (iteration >= 3) rows.push([iteration, waitingStarted, started, captured, recognized, completed, frame.frame]);
}
const final = await waitFor(color(23), { timeoutMs: 1500, intervalMs: 10 });
if (final.status !== 'found') await stop('O último toque não teve confirmação visual.');
await stage('concluido', 'Vinte amostras e três aquecimentos concluídos no alvo controlado.');
await log(JSON.stringify({ kind: 'latency_target_samples', round: options.round,
    columns: ['iteration', 'waitingStartedMs', 'startedMs', 'capturedMs', 'recognizedMs', 'completedMs', 'frame'], rows }));
