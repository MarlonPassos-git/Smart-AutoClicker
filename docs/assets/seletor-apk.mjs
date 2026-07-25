const RELEASES_API =
  "https://api.github.com/repos/MarlonPassos-git/Smart-AutoClicker/releases?per_page=10";
const RELEASES_PAGE =
  "https://github.com/MarlonPassos-git/Smart-AutoClicker/releases";

/**
 * Recomenda a ABI a partir das informações reduzidas expostas pelo navegador.
 * @example recomendarArquiteturaApk({ android: true, arquitetura: "arm", bits: "64" })
 */
export function recomendarArquiteturaApk({ android, arquitetura = "", bits = "" }) {
  if (!android) return "universal";

  const arquiteturaNormalizada = arquitetura.toLowerCase();
  if (arquiteturaNormalizada.includes("x86")) return bits === "64" ? "x86_64" : "x86";
  if (arquiteturaNormalizada.includes("arm")) return bits === "64" ? "arm64-v8a" : "armeabi-v7a";
  return "universal";
}

/**
 * Seleciona a release publicada mais recente que contém arquivos APK.
 * @example selecionarReleaseComApk([{ draft: false, assets: [{ name: "app.apk" }] }])
 */
export function selecionarReleaseComApk(releases) {
  if (!Array.isArray(releases)) return null;

  return releases.find((release) =>
    !release.draft && release.assets?.some((asset) => asset.name.endsWith(".apk"))
  ) ?? null;
}

/**
 * Localiza o APK da ABI escolhida e usa o universal como alternativa segura.
 * @example localizarAssetApk([{ name: "Klickr-universal.apk" }], "arm64-v8a")
 */
export function localizarAssetApk(assets, arquitetura) {
  if (!Array.isArray(assets)) return null;

  const apkAssets = assets.filter((asset) => asset.name.endsWith(".apk"));
  const arquiteturaExata = apkAssets.find((asset) => asset.name.includes(`-${arquitetura}-`));
  return arquiteturaExata ?? apkAssets.find((asset) => asset.name.includes("-universal-")) ?? null;
}

async function lerInformacoesDoAparelho(navegador) {
  const android = /Android/i.test(navegador.userAgent);
  if (!navegador.userAgentData?.getHighEntropyValues) {
    return { android, arquitetura: "", bits: "" };
  }

  const hints = await navegador.userAgentData.getHighEntropyValues(["architecture", "bitness"]);
  return { android, arquitetura: hints.architecture ?? "", bits: hints.bitness ?? "" };
}

function textoDoAparelho(android, arquitetura) {
  if (!android) return `Aparelho não identificado • ${arquitetura}`;
  if (arquitetura === "universal") return "Android • arquitetura não informada";
  return `Android • ${arquitetura}`;
}

function atualizarDownload(release, arquitetura, elementos) {
  const asset = localizarAssetApk(release?.assets, arquitetura);
  if (!asset) return mostrarFalhaDeDownload(elementos);

  elementos.download.href = asset.browser_download_url;
  elementos.download.removeAttribute("aria-disabled");
  elementos.download.querySelector("span").textContent = "Baixar APK recomendado";
  elementos.status.textContent = `Versão ${release.tag_name} pronta para baixar.`;
}

function mostrarFalhaDeDownload(elementos) {
  elementos.download.href = RELEASES_PAGE;
  elementos.download.removeAttribute("aria-disabled");
  elementos.download.querySelector("span").textContent = "Ver APKs disponíveis";
  elementos.status.dataset.state = "error";
  elementos.status.textContent = "Não foi possível localizar o arquivo automaticamente.";
}

function obterElementosDaPagina() {
  return {
    arquitetura: document.querySelector("#apk-architecture"),
    aparelho: document.querySelector("#device-status"),
    download: document.querySelector("#download-apk"),
    status: document.querySelector("#release-status"),
  };
}

async function carregarRelease(fetchImpl) {
  const response = await fetchImpl(RELEASES_API, {
    headers: { Accept: "application/vnd.github+json" },
  });
  if (!response.ok) throw new Error(`GitHub API respondeu ${response.status}; esperado status 200.`);
  return selecionarReleaseComApk(await response.json());
}

async function iniciarSeletorDeApk() {
  const elementos = obterElementosDaPagina();
  const aparelho = await lerInformacoesDoAparelho(window.navigator);
  const recomendada = recomendarArquiteturaApk(aparelho);

  elementos.arquitetura.value = recomendada;
  elementos.aparelho.textContent = textoDoAparelho(aparelho.android, recomendada);

  let release;
  try {
    release = await carregarRelease(window.fetch.bind(window));
    atualizarDownload(release, recomendada, elementos);
  } catch {
    mostrarFalhaDeDownload(elementos);
  }

  elementos.arquitetura.addEventListener("change", (event) => {
    elementos.aparelho.textContent = textoDoAparelho(aparelho.android, event.target.value);
    atualizarDownload(release, event.target.value, elementos);
  });
}

if (typeof document !== "undefined") {
  iniciarSeletorDeApk();
}
