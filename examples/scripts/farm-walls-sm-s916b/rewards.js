import { now, sleep, stage, log, stop } from "klickr";
import { snapshot, found, all, act, guard, position, tapStep } from "./screen.js";

const rewardNames = ["starBonusTitle", "starBonusClaim", "chestRoll", "chestContinue", "chestRewardNote", "village", "villageText"];
const hasBonus = current => all(current, ["starBonusTitle", "starBonusClaim"]);
const hasReward = current => all(current, ["chestContinue", "chestRewardNote"]);

/** Collect a recognized star bonus or finish its chest before authorizing village actions.
 * Example: await collectStarRewards(); returns false when no supported reward is visible. */
export async function collectStarRewards() {
    let current = await snapshot(rewardNames);
    if (!found(current.results.starBonusTitle) && !found(current.results.chestRoll) && !hasReward(current)) return false;
    const deadline = await now() + 45000;
    let claims = 0;
    let strikes = 0;
    let confirmations = 0;
    let chestSeen = false;
    while (await now() < deadline) {
        if (hasBonus(current)) {
            await stage("resgatar bônus de estrelas", "Resgatando o bônus antes de continuar o farm.");
            if (claims >= 3) await stop("O bônus de estrelas não saiu após três tentativas de resgate.");
            await guard("starBonusTitle", current);
            if (await act(current, [tapStep(current.results.starBonusClaim, 100, 850)])) claims++;
        } else if (found(current.results.chestRoll)) {
            chestSeen = true;
            await stage("abrir baú de estrelas", "Abrindo o baú; cada toque exige uma tentativa disponível na tela.");
            if (strikes >= 12) await stop("O baú de estrelas permaneceu fechado após 12 toques confirmados na tela.");
            await guard("chestRoll", current);
            if (await act(current, [tapStep(position(current, "chestStrike"), 100, 850)])) strikes++;
        } else if (found(current.results.chestContinue) && (chestSeen || hasReward(current))) {
            chestSeen = true;
            await stage("confirmar recompensa do baú", "Confirmando a recompensa e aguardando a vila.");
            if (confirmations >= 3) await stop("A recompensa do baú não saiu após três confirmações.");
            await guard("chestContinue", current);
            if (await act(current, [tapStep(current.results.chestContinue, 100, 850)])) confirmations++;
        } else if (chestSeen && confirmations > 0 && (found(current.results.village) || found(current.results.villageText))) {
            await log(JSON.stringify({ event: "star_chest_collected", claims, strikes, confirmations }));
            return true;
        } else {
            await sleep(150);
        }
        current = await snapshot(rewardNames);
    }
    await stop("Sem confirmação da abertura do baú e retorno à vila em 45 segundos; nenhum próximo ataque foi autorizado.");
}
