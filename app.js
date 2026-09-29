/* =============================================================
   Rechnungen – Belege für den Hausbau erfassen (PWA)
   Belege und Fotos bleiben lokal auf dem Gerät (IndexedDB).
   Der API-Schlüssel liegt nur im localStorage dieses Geräts
   und steht nie im Code oder im Repository.
   ============================================================= */
'use strict';

const APP_VERSION = '1.1.0';
const SET_KEY = 'rechnungen.v1';
const API_KEY_KEY = 'rechnungen.key';
const API_URL = 'https://api.anthropic.com/v1/messages';

/* Modelle – Preise in US-Dollar je Million Tokens (Eingabe / Ausgabe) */
const MODELS = [
  { id: 'claude-sonnet-5-5', name: 'Sonnet 5.5', effort: 'medium', pin: 2, pout: 10, hint: 'ca. 3–5 Cent je Beleg' },
  { id: 'claude-opus-5-5', name: 'Opus 5.5', effort: 'medium', pin: 4, pout: 20, hint: 'ca. 6–10 Cent je Beleg' },
  { id: 'claude-haiku-4-5-20251001', name: 'Haiku 4.5', effort: null, pin: 1, pout: 5, hint: 'ca. 1 Cent je Beleg' }
];
const DEFAULT_MODEL = MODELS[0].id;
const modelOf = id => MODELS.find(m => m.id === id) || MODELS[0];

/* Kategorienfarben (geprüfte Palette) – [hell, auf dunklem Grund] */
const PALETTE = [
  ['#2a78d6', '#3987e5'], ['#eb6834', '#e0703f'], ['#1baf7a', '#18a473'], ['#eda100', '#c08408'],
  ['#e87ba4', '#d46a95'], ['#008300', '#2e9e3a'], ['#4a3aa7', '#9085e9'], ['#e34948', '#e66767']
];
const GRAY = ['#8A94A6', '#6E7A8E'];
let THEME = 'light';
const catColor = c => { const p = c && c.c >= 0 && PALETTE[c.c] || GRAY; return THEME === 'dark' ? p[1] : p[0]; };

const DEFAULT_CATS = [
  ['Grundstück', 'Grundstückskauf, Notar, Grundbuch, Grunderwerbsteuer, Vermessung, Makler, Aufschließung'],
  ['Haus', 'Hausanbieter, Fertighaus, Generalunternehmer, Ausbaupaket'],
  ['Finanzierung', 'Bank, Kredit, Zinsen, Bearbeitungsgebühr, Schätzung, Versicherung'],
  ['Planung', 'Architekt, Einreichplanung, Statik, Bauphysik, Energieausweis, Baubewilligung, Behörden, Gebühren'],
  ['Rohbau', 'Baumeister, Erdarbeiten, Fundament, Bodenplatte, Mauerwerk, Beton, Decke, Dachstuhl, Baustoffe, Baustelleneinrichtung'],
  ['Gewerke', 'Elektro, Sanitär, Heizung, Lüftung, Fenster, Türen, Dachdecker, Spengler, Estrich, Putz, Fliesen, Maler, Tischler, Böden'],
  ['Verpflegung', 'Essen und Getränke für Helfer und Handwerker, Supermarkt, Bäckerei, Gasthaus, Jause']
];

const COUNTRIES = [['AT', 'Österreich'], ['DE', 'Deutschland'], ['IT', 'Italien'], ['CH', 'Schweiz'], ['SI', 'Slowenien'],
  ['CZ', 'Tschechien'], ['SK', 'Slowakei'], ['HU', 'Ungarn'], ['PL', 'Polen'], ['NL', 'Niederlande'], ['FR', 'Frankreich']];

const MONTHS = ['Jänner', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];

const ICON = {
  search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><line x1="20" y1="20" x2="16.2" y2="16.2"/></svg>',
  close: '<svg viewBox="0 0 24 24"><line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/></svg>',
  back: '<svg viewBox="0 0 24 24"><polyline points="15 5 8 12 15 19"/></svg>',
  chevL: '<svg viewBox="0 0 24 24"><polyline points="15 5 8 12 15 19"/></svg>',
  chevR: '<svg viewBox="0 0 24 24"><polyline points="9 5 16 12 9 19"/></svg>',
  go: '<svg viewBox="0 0 24 24"><polyline points="9 6 15 12 9 18"/></svg>',
  camera: '<svg viewBox="0 0 24 24"><path d="M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"/><circle cx="12" cy="13.5" r="3.5"/></svg>',
  image: '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><circle cx="9" cy="10" r="2"/><polyline points="21 16 16 11 6 20"/></svg>',
  pdf: '<svg viewBox="0 0 24 24"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><polyline points="14 3 14 8 19 8"/><line x1="9" y1="13" x2="15" y2="13"/><line x1="9" y1="17" x2="13" y2="17"/></svg>',
  rotate: '<svg viewBox="0 0 24 24"><path d="M20 11a8 8 0 1 0-2.3 5.7"/><polyline points="20 4 20 11 13 11"/></svg>',
  trash: '<svg viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6M10 11v6M14 11v6M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"/></svg>',
  plus: '<svg viewBox="0 0 24 24"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>',
  key: '<svg viewBox="0 0 24 24"><circle cx="8" cy="15" r="4"/><line x1="10.8" y1="12.2" x2="20" y2="3"/><line x1="17" y1="6" x2="20" y2="9"/><line x1="15" y1="8" x2="17" y2="10"/></svg>',
  spark: '<svg viewBox="0 0 24 24"><path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z"/><path d="M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z"/></svg>',
  tag: '<svg viewBox="0 0 24 24"><path d="M20.6 13.4l-7.2 7.2a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8z"/><circle cx="7.5" cy="7.5" r="1.5"/></svg>',
  table: '<svg viewBox="0 0 24 24"><rect x="3" y="4" width="18" height="16" rx="2"/><line x1="3" y1="10" x2="21" y2="10"/><line x1="9" y1="4" x2="9" y2="20"/></svg>',
  save: '<svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>',
  load: '<svg viewBox="0 0 24 24"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>',
  disk: '<svg viewBox="0 0 24 24"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/></svg>',
  redo: '<svg viewBox="0 0 24 24"><path d="M4 12a8 8 0 0 1 13.7-5.7L20 8.6"/><polyline points="20 3 20 9 14 9"/><path d="M20 12a8 8 0 0 1-13.7 5.7L4 15.4"/><polyline points="4 21 4 15 10 15"/></svg>',
  ok: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><polyline points="8 12.5 11 15.5 16 9.5"/></svg>',
  warn: '<svg viewBox="0 0 24 24"><path d="M12 3l10 18H2z"/><line x1="12" y1="10" x2="12" y2="14"/><line x1="12" y1="17.5" x2="12" y2="17.6"/></svg>',
  up: '<svg viewBox="0 0 24 24"><polyline points="6 15 12 9 18 15"/></svg>',
  copy: '<svg viewBox="0 0 24 24"><rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/></svg>',
  euro: '<svg viewBox="0 0 24 24"><path d="M18 6.5A7 7 0 1 0 18 17.5"/><line x1="4" y1="10" x2="13" y2="10"/><line x1="4" y1="14" x2="13" y2="14"/></svg>',
  bank: '<svg viewBox="0 0 24 24"><polyline points="3 10 12 4 21 10"/><line x1="5" y1="10" x2="5" y2="18"/><line x1="10" y1="10" x2="10" y2="18"/><line x1="14" y1="10" x2="14" y2="18"/><line x1="19" y1="10" x2="19" y2="18"/><line x1="3" y1="21" x2="21" y2="21"/></svg>'
};

/* ---------------- Hilfsfunktionen ---------------- */
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
let uidCounter = 0;
const uid = () => Date.now().toString(36) + (uidCounter++).toString(36) + Math.random().toString(36).slice(2, 7);
const sleep = ms => new Promise(r => setTimeout(r, ms));
const clone = o => JSON.parse(JSON.stringify(o));
const pad = n => String(n).padStart(2, '0');
const isoDay = d => d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
const today = () => isoDay(new Date());
const round2 = n => Math.round(n * 100) / 100;
const isNum = n => typeof n === 'number' && isFinite(n);
const sum = a => a.reduce((s, x) => s + (isNum(x) ? x : 0), 0);

function fmtNum(n, d = 2) {
  const f = Math.abs(n).toFixed(d).split('.');
  return (n < 0 && Math.abs(n) >= 0.5 * Math.pow(10, -d) ? '−' : '') + f[0].replace(/\B(?=(\d{3})+(?!\d))/g, '.') + (d ? ',' + f[1] : '');
}
const eur = n => isNum(n) ? fmtNum(n) + ' €' : '–';
const eur0 = n => isNum(n) ? fmtNum(Math.round(n), 0) + ' €' : '–';
const fmtIn = n => isNum(n) ? (n < 0 ? '-' : '') + fmtNum(Math.abs(n)) : '';
const fmtRate = n => isNum(n) ? String(round2(n)).replace('.', ',') : '';
function parseNum(s) {
  if (s == null) return null;
  s = String(s).trim().replace(/[\s€%]/g, '').replace(/[−–]/g, '-');
  if (s === '') return null;
  if (s.includes(',')) s = s.replace(/\./g, '').replace(',', '.');
  else if (/^-?\d{1,3}(\.\d{3})+$/.test(s)) s = s.replace(/\./g, '');
  const v = Number(s);
  return isFinite(v) ? round2(v) : NaN;
}
const fmtDate = d => d ? d.slice(8, 10) + '.' + d.slice(5, 7) + '.' + d.slice(0, 4) : '';
const fmtShort = d => d ? d.slice(8, 10) + '.' + d.slice(5, 7) + '.' : '';
const monthLabel = k => MONTHS[+k.slice(5, 7) - 1] + ' ' + k.slice(0, 4);
const validDay = s => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(s) && !isNaN(Date.parse(s)) ? s : '';

function toast(msg) {
  const t = $('#toast');
  t.textContent = msg; t.classList.add('show');
  clearTimeout(toast.t); toast.t = setTimeout(() => t.classList.remove('show'), 2600);
}
function download(name, blob) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a'); a.href = url; a.download = name;
  document.body.appendChild(a); a.click();
  setTimeout(() => { URL.revokeObjectURL(url); a.remove(); }, 4000);
}
function veil(text) {
  const d = document.createElement('div'); d.className = 'veil';
  d.innerHTML = `<div class="box"><span class="spin"></span><span>${esc(text)}</span></div>`;
  document.body.appendChild(d);
  return () => d.remove();
}
const toB64 = blob => new Promise((res, rej) => {
  const r = new FileReader();
  r.onload = () => res(String(r.result).split(',')[1]);
  r.onerror = () => rej(r.error);
  r.readAsDataURL(blob);
});
const canvasBlob = (c, type, q) => new Promise((res, rej) => c.toBlob(b => b ? res(b) : rej(new Error('Bild konnte nicht erzeugt werden')), type, q));

/* ---------------- Einstellungen (localStorage) ---------------- */
let S;
function defaultCats() { return DEFAULT_CATS.map(([name, hint], i) => ({ id: uid(), name, hint, c: i })); }
function loadSettings() {
  let s = null;
  try { s = JSON.parse(localStorage.getItem(SET_KEY) || 'null'); } catch (e) { }
  S = Object.assign({ v: 1, theme: 'system', model: DEFAULT_MODEL, cats: null, lastBackup: null, cost: 0 }, s && typeof s === 'object' ? s : {});
  if (!Array.isArray(S.cats)) S.cats = defaultCats();
  if (!MODELS.some(m => m.id === S.model)) S.model = DEFAULT_MODEL;
}
function saveSettings() {
  try { localStorage.setItem(SET_KEY, JSON.stringify(S)); } catch (e) { toast('Einstellungen konnten nicht gespeichert werden'); }
}
function apiKey() { try { return localStorage.getItem(API_KEY_KEY) || ''; } catch (e) { return ''; } }
const catOf = id => S.cats.find(c => c.id === id) || null;

const mq = window.matchMedia('(prefers-color-scheme: dark)');
function applyTheme() {
  const pref = S.theme || 'system';
  THEME = pref === 'system' ? (mq.matches ? 'dark' : 'light') : pref;
  document.documentElement.dataset.theme = THEME;
  const c = THEME === 'dark' ? '#0B1120' : '#F3F4F6';
  $$('meta[name=theme-color]').forEach(m => { m.content = c; });
}

/* ---------------- Datenbank (IndexedDB) ----------------
   belege:  ein Datensatz je Beleg (ohne Dateien, mit kleinem Vorschaubild)
   dateien: Fotos (JPEG) bzw. das Original-PDF, verknüpft über „beleg“ */
let idb;
const BL = new Map(); // alle Belege im Speicher
function openDB() {
  return new Promise((res, rej) => {
    const r = indexedDB.open('rechnungen', 1);
    r.onupgradeneeded = () => {
      const d = r.result;
      if (!d.objectStoreNames.contains('belege')) d.createObjectStore('belege', { keyPath: 'id' });
      if (!d.objectStoreNames.contains('dateien')) d.createObjectStore('dateien', { keyPath: 'id' }).createIndex('beleg', 'beleg');
    };
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
}
const reqP = r => new Promise((res, rej) => { r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
const dbGet = (store, id) => reqP(idb.transaction(store).objectStore(store).get(id));
const dbAll = store => reqP(idb.transaction(store).objectStore(store).getAll());
function dbWrite(fn) {
  return new Promise((res, rej) => {
    const t = idb.transaction(['belege', 'dateien'], 'readwrite');
    fn(t.objectStore('belege'), t.objectStore('dateien'));
    t.oncomplete = () => res();
    t.onerror = () => rej(t.error);
    t.onabort = () => rej(t.error || new Error('abgebrochen'));
  });
}
function storageError(e) {
  return e && e.name === 'QuotaExceededError' ? 'Speicher voll – bitte Sicherung erstellen und alte Belege löschen' : 'Speichern fehlgeschlagen';
}
async function putBeleg(b) {
  await dbWrite(sb => sb.put(b));
  BL.set(b.id, b);
}
async function filesOf(b) {
  const fs = await Promise.all((b.dateien || []).map(id => dbGet('dateien', id)));
  return fs.filter(Boolean).sort((a, c) => a.idx - c.idx);
}
/* Felder für Zahlung und Skonto (ab Version 1.1) – ältere Datensätze bekommen sie beim Laden */
const PAY_DEFAULTS = { empfaenger: '', iban: '', bic: '', referenz: '', skontoProz: null, skontoBis: '', skontoBetrag: null, bezahltAm: '', gezahlt: null };
function fillDefaults(b) { for (const k in PAY_DEFAULTS) if (b[k] === undefined) b[k] = PAY_DEFAULTS[k]; return b; }
function blankBeleg(art) {
  const now = new Date().toISOString();
  return {
    id: uid(), created: now, updated: now, status: 'warten', art, dateien: [], thumb: null,
    lieferant: '', land: '', nr: '', datum: '', faellig: '', kategorie: null, bezahlt: false, waehrung: 'EUR',
    steuer: [], netto: null, ust: null, brutto: null, zahlbetrag: null, positionen: [], notiz: '',
    ...PAY_DEFAULTS,
    unsicher: [], modell: null, ausgelesen: null, fehler: null, netzfehler: false, kosten: 0
  };
}
/* Betrag für Summen: bei bezahlten Belegen der gezahlte Betrag (z. B. mit Skonto),
   sonst der zu zahlende Betrag, sonst Brutto */
const offenBetrag = b => isNum(b.zahlbetrag) ? b.zahlbetrag : isNum(b.brutto) ? b.brutto : 0;
const betrag = b => b.bezahlt && isNum(b.gezahlt) ? b.gezahlt : offenBetrag(b);
const istOffen = b => b.status !== 'warten' && !b.bezahlt;
const istUeberfaellig = b => istOffen(b) && b.faellig && b.faellig < today();
const tagOf = b => b.datum || b.created.slice(0, 10);

/* ---------------- IBAN & Skonto ---------------- */
const IBAN_LEN = { AT: 20, DE: 22, IT: 27, CH: 21, LI: 21, SI: 19, CZ: 24, SK: 24, HU: 28, PL: 28, NL: 18, FR: 27, BE: 16, LU: 20, ES: 24, HR: 21 };
const normIban = s => String(s || '').replace(/[\s-]/g, '').toUpperCase();
const fmtIban = s => normIban(s).replace(/(.{4})/g, '$1 ').trim();
function ibanOk(s) {
  const x = normIban(s);
  if (!/^[A-Z]{2}\d{2}[A-Z0-9]{10,30}$/.test(x)) return false;
  if (IBAN_LEN[x.slice(0, 2)] && IBAN_LEN[x.slice(0, 2)] !== x.length) return false;
  let m = 0;
  for (const ch of x.slice(4) + x.slice(0, 4)) {
    const v = ch >= 'A' ? String(ch.charCodeAt(0) - 55) : ch;
    for (const d of v) m = (m * 10 + +d) % 97;
  }
  return m === 1;
}
/* Frühere IBANs desselben Lieferanten, die von dieser abweichen (Schutz vor gefälschten Rechnungen) */
function otherIbans(w) {
  const iban = normIban(w.iban), lf = normName(w.lieferant);
  if (!iban || !lf) return [];
  return [...new Set([...BL.values()].filter(o => o.id !== w.id && o.iban && sameName(lf, normName(o.lieferant)))
    .map(o => normIban(o.iban)).filter(x => x !== iban))];
}
function skontoInfo(b) {
  const full = offenBetrag(b);
  if (!isNum(b.skontoProz) || b.skontoProz <= 0 || !(full > 0)) return { active: false };
  const amount = isNum(b.skontoBetrag) ? b.skontoBetrag : round2(full * (1 - b.skontoProz / 100));
  return { active: !b.skontoBis || b.skontoBis >= today(), amount, bis: b.skontoBis, saving: round2(full - amount), full };
}

/* ---------------- Bilder & PDF ---------------- */
/* Bildgröße passend zur Bildauflösung von Claude: höchstens 2576 px lange Kante
   und rund 4700 Bildkacheln à 28×28 px – mehr würde Claude ohnehin verkleinern. */
function fitDims(w, h) {
  let s = Math.min(1, 2576 / Math.max(w, h));
  while (Math.ceil(w * s / 28) * Math.ceil(h * s / 28) > 4700) s *= 0.97;
  return [Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s))];
}
async function processImage(src, rotate = 0) {
  const bmp = await createImageBitmap(src, { imageOrientation: 'from-image' });
  let w = bmp.width, h = bmp.height;
  if (rotate % 180) [w, h] = [h, w];
  const [tw, th] = fitDims(w, h);
  const c = document.createElement('canvas'); c.width = tw; c.height = th;
  const x = c.getContext('2d');
  x.fillStyle = '#fff'; x.fillRect(0, 0, tw, th);
  x.imageSmoothingQuality = 'high';
  x.translate(tw / 2, th / 2); x.rotate(rotate * Math.PI / 180);
  const dw = rotate % 180 ? th : tw, dh = rotate % 180 ? tw : th;
  x.drawImage(bmp, -dw / 2, -dh / 2, dw, dh);
  if (bmp.close) bmp.close();
  const blob = await canvasBlob(c, 'image/jpeg', 0.85);
  c.width = c.height = 0;
  return blob;
}
async function makeThumb(blob) {
  const bmp = await createImageBitmap(blob, { resizeWidth: 120, resizeQuality: 'high' });
  const c = document.createElement('canvas'); c.width = bmp.width; c.height = bmp.height;
  c.getContext('2d').drawImage(bmp, 0, 0);
  if (bmp.close) bmp.close();
  return c.toDataURL('image/jpeg', 0.72);
}
let pdfLib = null;
async function pdfjs() {
  if (!pdfLib) {
    pdfLib = await import('./lib/pdfjs/pdf.min.js');
    pdfLib.GlobalWorkerOptions.workerSrc = new URL('lib/pdfjs/pdf.worker.min.js', location.href).href;
  }
  return pdfLib;
}
async function openPdf(blob) {
  const lib = await pdfjs();
  return lib.getDocument({
    data: new Uint8Array(await blob.arrayBuffer()),
    wasmUrl: new URL('lib/pdfjs/wasm/', location.href).href,
    useSystemFonts: true, isEvalSupported: false
  }).promise;
}
function closePdf(doc) {
  try { (doc.loadingTask || doc).destroy(); } catch (e) { }
}
async function renderPdfPage(doc, n, width) {
  const page = await doc.getPage(n);
  const vp = page.getViewport({ scale: width / page.getViewport({ scale: 1 }).width });
  const c = document.createElement('canvas');
  c.width = Math.round(vp.width); c.height = Math.round(vp.height);
  const ctx = c.getContext('2d');
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height);
  await page.render({ canvas: c, canvasContext: ctx, viewport: vp }).promise;
  const b = await canvasBlob(c, 'image/jpeg', 0.85);
  c.width = c.height = 0;
  page.cleanup();
  return b;
}

/* =============================================================
   Auslesen mit Claude
   ============================================================= */
const SYSTEM_PROMPT = `Du liest Rechnungen und Belege für einen privaten Hausbau in Österreich aus. Du bekommst Fotos (eine oder mehrere Seiten desselben Belegs) oder ein PDF und gibst die Daten im vorgegebenen JSON-Format zurück.

Regeln:
- Übernimm nur, was auf dem Beleg steht, und rate nichts. Fehlt ein Wert, setze null.
- Beträge als Zahl mit Punkt als Dezimaltrennzeichen, ohne Tausenderpunkte und ohne Währungszeichen (1.234,56 € wird 1234.56).
- Datumsangaben als JJJJ-MM-TT. Belege aus Österreich und Deutschland schreiben das Datum als TT.MM.JJJJ.
- lieferant: Firmenname des Rechnungsausstellers (nicht des Empfängers), ohne Adresse.
- land: Land des Ausstellers als zweistelliger ISO-Code (AT, DE, IT, …), erkennbar an Adresse, UID-Nummer (ATU…, DE…) oder Telefonvorwahl.
- rechnungsnummer: genau wie gedruckt.
- faelligkeitsdatum: ausdrücklich genanntes Zahlungsziel; bei „zahlbar binnen N Tagen“ Rechnungsdatum plus N Tage; bei „sofort fällig“ das Rechnungsdatum; sonst null. Eine Skontofrist ist nicht das Fälligkeitsdatum.
- waehrung: ISO-Code der Währung (EUR, CHF, …).
- steuer: ein Eintrag je Umsatzsteuersatz auf dem Beleg mit Satz in Prozent, Nettobetrag und Steuerbetrag. Steuerfreie Beträge, Reverse Charge oder Kleinunternehmer: Satz 0 und Steuer 0. Nennt ein Kassenbon nur Brutto und enthaltene Steuer, berechne Netto = Brutto − Steuer.
- netto, ust, brutto: Summen des Belegs. Prüfe, ob netto + ust = brutto ergibt und ob die Steuerbeträge zu den Sätzen passen. Weichen die gedruckten Werte ab, übernimm trotzdem die gedruckten Werte und nenne die Abweichung im Hinweis.
- zahlbetrag: tatsächlich zu zahlender Endbetrag nach Abzug von Anzahlungen, Teilzahlungen, Abschlagsrechnungen oder Haftrücklass. Ist nichts abgezogen, gleich brutto. Skonto nicht abziehen.
- Gutschriften: Beträge negativ.
- bezahlt: true nur, wenn der Beleg eindeutig zeigt, dass schon bezahlt wurde (Kassenbon, Barzahlung, Kartenzahlung, „bezahlt“, „Betrag erhalten“). Sonst false.
- kategorie: die passendste Kategorie aus der Liste; passt keine, null.
- positionen: jede Rechnungsposition mit Bezeichnung (kurz, wie gedruckt), Menge mit Einheit wie gedruckt und Gesamtbetrag der Zeile. Keine Zwischensummen, Steuerzeilen oder Überträge.
- empfaenger: Kontoinhaber laut Bankverbindung des Ausstellers; steht keiner dabei, null.
- iban: IBAN des Ausstellers ohne Leerzeichen, Zeichen für Zeichen genau wie gedruckt. Bei mehreren Bankverbindungen die erste. Nicht die IBAN des Kunden: Wird der Betrag abgebucht (Lastschrift, Einzug), setze null und nenne das im Hinweis.
- bic: BIC des Ausstellers, sonst null.
- zahlungsreferenz: was laut Beleg bei der Überweisung als Zahlungsreferenz oder Verwendungszweck anzugeben ist; sonst null.
- skonto_prozent, skonto_frist, skonto_betrag: Skonto in Prozent, letzter Tag der Skontofrist (bei „binnen N Tagen“ Rechnungsdatum plus N Tage) und der Zahlbetrag mit Skonto, falls gedruckt. Ohne Skonto jeweils null.
- hinweis: kurzer deutscher Hinweis auf Wichtiges, sonst null: Haftrücklass, abgezogene Anzahlungen, Abschlags- oder Schlussrechnung, Reverse Charge, Abbuchung per Lastschrift, andere Währung als Euro, fehlende oder abgeschnittene Seiten, Rechenfehler auf dem Beleg. Skonto nur in den Skonto-Feldern.
- unsicher: Namen der Felder, die schlecht lesbar, abgeschnitten, handschriftlich oder widersprüchlich sind.`;

const UNSURE_FIELDS = ['lieferant', 'land', 'rechnungsnummer', 'rechnungsdatum', 'faelligkeitsdatum', 'steuer', 'netto', 'ust', 'brutto', 'zahlbetrag', 'kategorie', 'positionen', 'iban', 'skonto'];
function schema() {
  const names = [...new Set(S.cats.map(c => c.name))];
  const nstr = { type: ['string', 'null'] }, nnum = { type: ['number', 'null'] };
  const date = { anyOf: [{ type: 'string', format: 'date' }, { type: 'null' }] };
  const props = {
    lieferant: nstr, land: nstr, rechnungsnummer: nstr,
    rechnungsdatum: date, faelligkeitsdatum: date, waehrung: nstr,
    steuer: {
      type: 'array', items: {
        type: 'object', additionalProperties: false, required: ['satz', 'netto', 'ust'],
        properties: { satz: { type: 'number' }, netto: nnum, ust: nnum }
      }
    },
    netto: nnum, ust: nnum, brutto: nnum, zahlbetrag: nnum,
    bezahlt: { type: 'boolean' },
    kategorie: names.length ? { anyOf: [{ type: 'string', enum: names }, { type: 'null' }] } : { type: 'null' },
    positionen: {
      type: 'array', items: {
        type: 'object', additionalProperties: false, required: ['text', 'menge', 'betrag'],
        properties: { text: { type: 'string' }, menge: nstr, betrag: nnum }
      }
    },
    empfaenger: nstr, iban: nstr, bic: nstr, zahlungsreferenz: nstr,
    skonto_prozent: nnum, skonto_frist: date, skonto_betrag: nnum,
    hinweis: nstr,
    unsicher: { type: 'array', items: { type: 'string', enum: UNSURE_FIELDS } }
  };
  return { type: 'object', additionalProperties: false, required: Object.keys(props), properties: props };
}
function userText() {
  const cats = S.cats.map(k => '- ' + k.name + (k.hint ? ': ' + k.hint : '')).join('\n');
  return 'Kategorien:\n' + (cats || '(keine)') + '\n\nLies den Beleg aus.';
}
async function buildContent(b, files) {
  const c = [];
  if (b.art === 'pdf') {
    c.push({ type: 'document', source: { type: 'base64', media_type: 'application/pdf', data: await toB64(files[0].blob) } });
  } else {
    for (let i = 0; i < files.length; i++) {
      if (files.length > 1) c.push({ type: 'text', text: 'Seite ' + (i + 1) + ':' });
      c.push({ type: 'image', source: { type: 'base64', media_type: files[i].type || 'image/jpeg', data: await toB64(files[i].blob) } });
    }
  }
  c.push({ type: 'text', text: userText() });
  return c;
}
function apiHeaders(key) {
  return {
    'x-api-key': key,
    'anthropic-version': '2023-06-01',
    'content-type': 'application/json',
    'anthropic-dangerous-direct-browser-access': 'true'
  };
}
function apiError(status, msg) {
  if (status === 401) return 'API-Schlüssel ungültig';
  if (status === 403) return 'Kein Zugriff mit diesem API-Schlüssel';
  if (/anthropic-workspace-id/i.test(msg)) return 'Schlüssel ist keinem Workspace zugeordnet – neuen Schlüssel für einen Workspace anlegen';
  if (/credit balance/i.test(msg)) return 'Guthaben in der Anthropic Console aufgebraucht';
  if (status === 413) return 'Beleg zu groß';
  if (status === 429) return 'Zu viele Anfragen – bitte kurz warten';
  if (status === 529 || status >= 500) return 'Claude ist gerade überlastet – bitte später erneut versuchen';
  return 'Fehler ' + status + (msg ? ': ' + msg : '');
}
function parseJSON(text) {
  try { return JSON.parse(text); } catch (e) { }
  const a = text.indexOf('{'), z = text.lastIndexOf('}');
  if (a >= 0 && z > a) { try { return JSON.parse(text.slice(a, z + 1)); } catch (e) { } }
  return null;
}
async function callClaude(content, modelId) {
  const key = apiKey();
  if (!key) throw new Error('Kein API-Schlüssel eingetragen');
  const model = modelOf(modelId);
  let useFormat = true;
  for (let attempt = 0; ; attempt++) {
    const body = { model: model.id, max_tokens: 16000, system: SYSTEM_PROMPT, messages: [{ role: 'user', content }] };
    const oc = {};
    if (model.effort) oc.effort = model.effort;
    if (useFormat) oc.format = { type: 'json_schema', schema: schema() };
    if (Object.keys(oc).length) body.output_config = oc;
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 300000);
    let r;
    try {
      r = await fetch(API_URL, { method: 'POST', headers: apiHeaders(key), body: JSON.stringify(body), signal: ctl.signal });
    } catch (e) {
      const err = new Error(e.name === 'AbortError' ? 'Zeitüberschreitung – bitte erneut versuchen' : 'Keine Verbindung zu Claude');
      err.netz = true; throw err;
    } finally { clearTimeout(timer); }
    if (r.ok) {
      const j = await r.json();
      if (j.stop_reason === 'refusal') throw new Error('Claude hat das Auslesen abgelehnt');
      if (j.stop_reason === 'max_tokens') throw new Error('Antwort unvollständig – Beleg zu umfangreich');
      const text = (j.content || []).filter(c => c.type === 'text').map(c => c.text).join('');
      const data = parseJSON(text);
      if (!data || typeof data !== 'object') throw new Error('Antwort von Claude nicht lesbar');
      return { data, usage: j.usage || {}, model };
    }
    let j = null;
    try { j = await r.json(); } catch (e) { }
    const msg = (j && j.error && j.error.message) || '';
    // Falls das Modell das JSON-Schema nicht annimmt: ohne Schema erneut versuchen
    if (r.status === 400 && useFormat && /output_config|format|schema|grammar/i.test(msg)) { useFormat = false; continue; }
    if ((r.status === 429 || r.status >= 500) && attempt < 2) { await sleep(r.status === 429 ? 10000 : 4000 * (attempt + 1)); continue; }
    throw new Error(apiError(r.status, msg));
  }
}
const str = v => typeof v === 'string' ? v.trim() : '';
const numOr = v => isNum(v) ? round2(v) : null;
const arr = v => Array.isArray(v) ? v : [];
function applyExtraction(b, d) {
  b.lieferant = str(d.lieferant);
  b.land = str(d.land).toUpperCase().slice(0, 2);
  b.nr = str(d.rechnungsnummer);
  b.datum = validDay(d.rechnungsdatum);
  b.faellig = validDay(d.faelligkeitsdatum);
  b.waehrung = (str(d.waehrung) || 'EUR').toUpperCase();
  b.steuer = arr(d.steuer).filter(s => s && typeof s === 'object').map(s => ({ satz: numOr(s.satz), netto: numOr(s.netto), ust: numOr(s.ust) }));
  b.netto = numOr(d.netto); b.ust = numOr(d.ust); b.brutto = numOr(d.brutto); b.zahlbetrag = numOr(d.zahlbetrag);
  if (!b.steuer.length && (isNum(b.netto) || isNum(b.ust))) b.steuer = [{ satz: null, netto: b.netto, ust: b.ust }];
  b.bezahlt = d.bezahlt === true;
  const cat = S.cats.find(c => c.name === d.kategorie);
  b.kategorie = cat ? cat.id : null;
  b.positionen = arr(d.positionen).filter(p => p && typeof p === 'object').map(p => ({ text: str(p.text), menge: str(p.menge), betrag: numOr(p.betrag) }));
  const h = str(d.hinweis);
  if (h && !b.notiz.includes(h)) b.notiz = b.notiz ? b.notiz + '\n' + h : h;
  b.unsicher = arr(d.unsicher).filter(x => UNSURE_FIELDS.includes(x));
  b.empfaenger = str(d.empfaenger);
  b.iban = normIban(str(d.iban));
  b.bic = str(d.bic).replace(/\s/g, '').toUpperCase();
  b.referenz = str(d.zahlungsreferenz);
  b.skontoProz = numOr(d.skonto_prozent);
  b.skontoBis = validDay(d.skonto_frist);
  b.skontoBetrag = numOr(d.skonto_betrag);
  if (b.bezahlt && !b.bezahltAm) b.bezahltAm = b.datum;
  if (b.iban && !ibanOk(b.iban) && !b.unsicher.includes('iban')) b.unsicher.push('iban');
}

const running = new Map(); // Beleg-ID → laufendes Auslesen
function extract(id, modelId = S.model) {
  if (running.has(id)) return running.get(id);
  const p = (async () => {
    const b = BL.get(id);
    if (!b) return;
    try {
      if (!apiKey()) throw new Error('Kein API-Schlüssel eingetragen');
      if (!navigator.onLine) { const e = new Error('Keine Internetverbindung'); e.netz = true; throw e; }
      const content = await buildContent(b, await filesOf(b));
      const { data, usage, model } = await callClaude(content, modelId);
      const cur = BL.get(id);
      if (!cur) return; // inzwischen gelöscht
      applyExtraction(cur, data);
      const cost = ((usage.input_tokens || 0) * model.pin + (usage.output_tokens || 0) * model.pout) / 1e6;
      Object.assign(cur, { status: 'pruefen', modell: model.id, ausgelesen: new Date().toISOString(), fehler: null, netzfehler: false, kosten: (cur.kosten || 0) + cost, updated: new Date().toISOString() });
      S.cost = (S.cost || 0) + cost; saveSettings();
      await putBeleg(cur);
      if (!onDetail(id)) toast('Beleg ausgelesen');
    } catch (e) {
      const cur = BL.get(id);
      if (!cur) return;
      cur.fehler = e.message || String(e); cur.netzfehler = !!e.netz;
      try { await putBeleg(cur); } catch (x) { }
      if (!onDetail(id)) toast('Auslesen fehlgeschlagen');
    }
  })().finally(() => { running.delete(id); changed(id); });
  running.set(id, p);
  changed(id);
  return p;
}
/* Belege, die noch warten (z. B. offline erfasst), der Reihe nach auslesen */
let queueBusy = false;
async function processQueue(all = false) {
  if (queueBusy || !apiKey() || !navigator.onLine) return;
  queueBusy = true;
  try {
    const list = [...BL.values()].filter(b => b.status === 'warten' && (all || !b.fehler || b.netzfehler) && !running.has(b.id))
      .sort((a, c) => a.created.localeCompare(c.created));
    for (const b of list) {
      if (!navigator.onLine) break;
      if (BL.has(b.id)) await extract(b.id);
    }
  } finally { queueBusy = false; }
}

/* =============================================================
   Router
   ============================================================= */
const ui = { view: '', q: '', filter: 'alle', listScroll: 0 };
let curHash = location.hash;
let D = null;  // Entwurf auf der Erfassen-Seite
let DS = null; // Bearbeitungsstand auf der Beleg-Seite
function unsaved() {
  return (ui.view === 'beleg' && DS && DS.dirty) || (ui.view === 'neu' && D && (D.pages.length || D.pdf));
}
function confirmLeave() {
  if (!unsaved()) return true;
  if (!confirm('Änderungen verwerfen?')) return false;
  if (DS) DS.dirty = false;
  if (D) { freeDraft(); D = null; }
  return true;
}
function setTab(name) { $$('#tabbar a').forEach(a => a.classList.toggle('on', a.dataset.tab === name)); }
function route() {
  if (sheetOpen) removeSheet();
  if (viewerOpen) removeViewer();
  if (maybeReload()) return;
  const h = location.hash.replace(/^#\/?/, '');
  const [p, a, sub] = h.split('/');
  if (ui.view === 'belege') ui.listScroll = window.scrollY;
  const full = p === 'neu' || p === 'beleg';
  document.body.classList.toggle('no-tabs', full);
  if (p !== 'beleg') { DS = null; freePages(); }
  if (p !== 'neu') D = null;
  ui.view = p || 'belege';
  setTab(ui.view);
  if (p === 'uebersicht') renderOverview();
  else if (p === 'neu') renderCapture();
  else if (p === 'beleg' && a) renderDetail(a);
  else if (p === 'einstellungen' && sub === undefined && a === 'kategorien') renderCats();
  else if (p === 'einstellungen') renderSettings();
  else { ui.view = 'belege'; setTab('belege'); renderList(); window.scrollTo(0, ui.listScroll || 0); return; }
  window.scrollTo(0, 0);
}
function go(hash, replace = true) {
  if (replace) { history.replaceState(history.state, '', hash); curHash = location.hash; route(); }
  else location.hash = hash;
}
function back(fallback = '#/') {
  if (history.state && history.state.app) history.back();
  else if (confirmLeave()) go(fallback);
}
window.addEventListener('hashchange', () => {
  if (location.hash === curHash) return;
  if (!confirmLeave()) { history.replaceState(history.state, '', curHash); return; }
  curHash = location.hash;
  route();
});
/* Navigation innerhalb der App merkt sich, dass „Zurück“ in der App bleibt */
function nav(hash) { history.pushState({ app: 1 }, '', hash); curHash = location.hash; route(); }
document.addEventListener('click', e => {
  const a = e.target.closest('a[href^="#/"]');
  if (!a || e.defaultPrevented) return;
  e.preventDefault();
  if (a.getAttribute('href') === location.hash) return;
  if (!confirmLeave()) return;
  if (a.closest('#tabbar')) go(a.getAttribute('href')); else nav(a.getAttribute('href'));
});
/* Nach Änderungen an einem Beleg die aktuelle Ansicht auffrischen */
function changed(id) {
  if (ui.view === 'belege') renderResults();
  else if (ui.view === 'uebersicht') renderOverview();
  else if (ui.view === 'beleg' && DS && DS.id === id && !DS.dirty) renderDetail(id, true);
}
const onDetail = id => ui.view === 'beleg' && DS && DS.id === id;

/* ---------------- Bottom-Sheet ---------------- */
let sheetOpen = false;
function openSheet(html) {
  const root = $('#sheet-root');
  if (!sheetOpen) {
    root.innerHTML = '<div class="backdrop"></div><div class="sheet"></div>';
    $('.backdrop', root).onclick = closeSheet;
    history.pushState({ sheet: 1, app: 1 }, '');
    sheetOpen = true;
  }
  const sh = $('.sheet', root);
  sh.innerHTML = '<div class="grab"></div>' + html;
  sh.scrollTop = 0;
  return sh;
}
function removeSheet() { $('#sheet-root').innerHTML = ''; sheetOpen = false; }
function closeSheet() { if (sheetOpen) { removeSheet(); history.back(); } }

/* ---------------- Vollbild mit Zoomen ---------------- */
let viewerOpen = false;
function removeViewer() { $('#viewer-root').innerHTML = ''; viewerOpen = false; }
window.addEventListener('popstate', () => {
  if (viewerOpen) removeViewer();
  if (sheetOpen) removeSheet();
});
function openViewer(srcs, start) {
  const root = $('#viewer-root');
  root.innerHTML = `<div class="viewer"><div class="vbar"><button class="iconbtn" id="vx" aria-label="Schließen">${ICON.close}</button>
    <div class="grow" id="vn"></div><button class="iconbtn" id="vp" aria-label="Vorherige Seite">${ICON.chevL}</button>
    <button class="iconbtn" id="vf" aria-label="Nächste Seite">${ICON.chevR}</button></div><img id="vi" alt=""></div>`;
  history.pushState({ viewer: 1, app: 1 }, '');
  viewerOpen = true;
  const box = $('.viewer', root), img = $('#vi', root);
  const multi = srcs.length > 1;
  $('#vp').hidden = $('#vf').hidden = !multi;
  let i = start, s = 1, tx = 0, ty = 0, fw = 0, fh = 0;
  const pts = new Map();
  let pinch = null, last = null, startX = 0, moved = false, tapT = 0, tapX = 0, tapY = 0;
  const vw = () => box.clientWidth, vh = () => box.clientHeight;
  function clamp() {
    const W = fw * s, H = fh * s;
    tx = W <= vw() ? (vw() - W) / 2 : Math.min(0, Math.max(vw() - W, tx));
    ty = H <= vh() ? (vh() - H) / 2 : Math.min(0, Math.max(vh() - H, ty));
  }
  const apply = () => { img.style.transform = `translate(${tx}px,${ty}px) scale(${s})`; };
  function layout() {
    const k = Math.min(vw() / img.naturalWidth, vh() / img.naturalHeight);
    fw = img.naturalWidth * k; fh = img.naturalHeight * k;
    img.style.width = fw + 'px'; img.style.height = fh + 'px';
    s = 1; clamp(); apply();
  }
  function zoomTo(px, py, ns) {
    ns = Math.max(1, Math.min(6, ns));
    tx = px - (px - tx) * ns / s; ty = py - (py - ty) * ns / s; s = ns;
    clamp(); apply();
  }
  function show(n) {
    i = (n + srcs.length) % srcs.length;
    img.onload = layout; img.src = srcs[i];
    $('#vn').textContent = multi ? (i + 1) + ' / ' + srcs.length : '';
  }
  const dist = (a, b) => Math.hypot(a.x - b.x, a.y - b.y);
  box.addEventListener('pointerdown', e => {
    if (e.target.closest('.vbar')) return;
    box.setPointerCapture(e.pointerId);
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pts.size === 1) { moved = false; startX = e.clientX; last = { x: e.clientX, y: e.clientY }; }
    if (pts.size === 2) {
      const [a, b] = [...pts.values()];
      pinch = { d: dist(a, b), s, tx, ty, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 };
      moved = true;
    }
  });
  box.addEventListener('pointermove', e => {
    if (!pts.has(e.pointerId)) return;
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pts.size >= 2 && pinch) {
      const [a, b] = [...pts.values()];
      const ns = Math.max(1, Math.min(6, pinch.s * dist(a, b) / pinch.d));
      const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
      tx = mx - (pinch.mx - pinch.tx) * ns / pinch.s; ty = my - (pinch.my - pinch.ty) * ns / pinch.s; s = ns;
      clamp(); apply();
    } else if (pts.size === 1 && last) {
      const dx = e.clientX - last.x, dy = e.clientY - last.y;
      if (Math.abs(e.clientX - startX) > 6 || Math.abs(dy) > 6) moved = true;
      if (s > 1) { tx += dx; ty += dy; clamp(); apply(); }
      last = { x: e.clientX, y: e.clientY };
    }
  });
  const up = e => {
    if (!pts.has(e.pointerId)) return;
    pts.delete(e.pointerId);
    if (pts.size < 2) pinch = null;
    if (pts.size === 1) { const p = [...pts.values()][0]; last = { x: p.x, y: p.y }; return; }
    if (pts.size) return;
    if (!moved) {
      const t = Date.now();
      if (t - tapT < 320 && Math.hypot(e.clientX - tapX, e.clientY - tapY) < 30) { zoomTo(e.clientX, e.clientY, s > 1 ? 1 : 2.5); tapT = 0; }
      else { tapT = t; tapX = e.clientX; tapY = e.clientY; }
    } else if (s === 1 && multi && Math.abs(e.clientX - startX) > 60) show(i + (e.clientX < startX ? 1 : -1));
  };
  box.addEventListener('pointerup', up);
  box.addEventListener('pointercancel', up);
  $('#vx').onclick = () => { if (viewerOpen) { removeViewer(); history.back(); } };
  $('#vp').onclick = () => show(i - 1);
  $('#vf').onclick = () => show(i + 1);
  show(i);
}

/* =============================================================
   Seite: Belege (Liste mit Suche)
   ============================================================= */
function renderList() {
  $('#view').innerHTML = `
    <div class="titlebar"><h1 class="left">Belege</h1></div>
    <div class="search">${ICON.search}<input id="q" type="search" placeholder="Suchen" autocomplete="off" enterkeyhint="search" value="${esc(ui.q)}">
      <button class="clear" id="qx" aria-label="Suche leeren" ${ui.q ? '' : 'hidden'}>${ICON.close}</button></div>
    <div class="chips" id="chips"></div>
    <div id="res"></div>`;
  $('#q').oninput = e => { ui.q = e.target.value; $('#qx').hidden = !ui.q; renderResults(); };
  $('#q').onkeydown = e => { if (e.key === 'Enter') e.target.blur(); };
  $('#qx').onclick = () => { ui.q = ''; $('#q').value = ''; $('#qx').hidden = true; renderResults(); };
  renderResults();
}
function haystack(b) {
  const c = catOf(b.kategorie);
  return [b.lieferant, b.nr, b.notiz, c && c.name, fmtDate(b.datum), b.land,
    isNum(b.brutto) ? fmtNum(b.brutto) + ' ' + String(b.brutto).replace('.', ',') : '',
    isNum(b.zahlbetrag) ? fmtNum(b.zahlbetrag) : '', b.empfaenger, b.iban, b.referenz,
    ...(b.positionen || []).map(p => p.text)].join(' ').toLowerCase();
}
function matchFilter(b, f) {
  if (f === 'offen') return istOffen(b);
  if (f === 'pruefen') return b.status !== 'ok';
  if (f === 'ohne') return b.status !== 'warten' && !catOf(b.kategorie);
  if (f.startsWith('c:')) return b.kategorie === f.slice(2);
  return true;
}
function renderResults() {
  const res = $('#res'), chipsEl = $('#chips');
  if (!res) return;
  const all = [...BL.values()];
  const cnt = f => all.filter(b => matchFilter(b, f)).length;
  const chips = [['alle', 'Alle', null], ['offen', 'Offen', null]];
  if (cnt('pruefen') || ui.filter === 'pruefen') chips.push(['pruefen', 'Zu prüfen', null]);
  S.cats.forEach(c => chips.push(['c:' + c.id, c.name, catColor(c)]));
  if (cnt('ohne') || ui.filter === 'ohne') chips.push(['ohne', 'Ohne Kategorie', null]);
  if (!chips.some(c => c[0] === ui.filter)) ui.filter = 'alle';
  chipsEl.innerHTML = chips.map(([k, l, col]) => {
    const n = k === 'alle' ? 0 : cnt(k);
    return `<button data-f="${esc(k)}" class="${ui.filter === k ? 'on' : ''}">${col ? `<i style="background:${col}"></i>` : ''}${esc(l)}${n ? ` <b>${n}</b>` : ''}</button>`;
  }).join('');
  $$('button', chipsEl).forEach(b => b.onclick = () => { ui.filter = b.dataset.f; renderResults(); });

  if (!all.length) {
    res.innerHTML = `<section class="card empty"><p>Noch keine Belege</p><a class="btn" href="#/neu">${ICON.camera}Beleg erfassen</a></section>`;
    return;
  }
  const words = ui.q.toLowerCase().split(/\s+/).filter(Boolean);
  const list = all.filter(b => matchFilter(b, ui.filter) && (!words.length || words.every(w => haystack(b).includes(w))))
    .sort((a, c) => tagOf(c).localeCompare(tagOf(a)) || c.created.localeCompare(a.created));
  if (!list.length) { res.innerHTML = '<section class="card empty"><p>Keine Treffer</p></section>'; return; }
  const groups = new Map();
  for (const b of list) {
    const k = tagOf(b).slice(0, 7);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(b);
  }
  res.innerHTML = [...groups].map(([k, bs]) => `
    <div class="mhead"><span>${esc(monthLabel(k))}</span><span>${eur(sum(bs.filter(b => b.status !== 'warten').map(betrag)))}</span></div>
    <section class="card">${bs.map(rowHTML).join('')}</section>`).join('');
}
function statusHTML(b) {
  if (b.status === 'warten') {
    if (running.has(b.id)) return '<span class="spin sm"></span>';
    return b.fehler && !b.netzfehler ? '<span class="badge err">Fehler</span>' : '<span class="badge">wartet</span>';
  }
  if (b.status === 'pruefen') return '<span class="badge acc">prüfen</span>';
  if (b.bezahlt) return 'bezahlt';
  if (istUeberfaellig(b)) return '<span class="st late">überfällig</span>';
  const sk = skontoInfo(b);
  if (sk.active && sk.bis) return '<span class="st open">Skonto bis ' + fmtShort(sk.bis) + '</span>';
  return '<span class="st open">' + (b.faellig ? 'fällig ' + fmtShort(b.faellig) : 'offen') + '</span>';
}
function rowHTML(b) {
  const c = catOf(b.kategorie);
  const title = b.lieferant || (b.status === 'warten' ? (running.has(b.id) ? 'Wird ausgelesen …' : 'Noch nicht ausgelesen') : 'Ohne Lieferant');
  const t2 = [b.datum ? fmtDate(b.datum) : fmtDate(b.created.slice(0, 10)), c ? esc(c.name) : '', b.nr ? 'Nr. ' + esc(b.nr) : ''].filter(Boolean).join(' · ');
  const thumb = b.thumb ? `<span class="thumb" style="background-image:url('${b.thumb}')"></span>` : `<span class="thumb">${b.art === 'pdf' ? ICON.pdf : ICON.image}</span>`;
  const st = statusHTML(b);
  return `<a class="brow" href="#/beleg/${esc(b.id)}">${thumb}
    <span class="mid"><span class="t1">${esc(title)}</span><span class="t2">${c ? `<i style="background:${catColor(c)}"></i>` : ''}<span>${t2}</span></span></span>
    <div class="right"><div class="amt">${b.status === 'warten' ? '' : eur(betrag(b))}</div><div class="st">${st}</div></div></a>`;
}

/* =============================================================
   Seite: Übersicht
   ============================================================= */
function sharePct(v, total) {
  if (!total) return '0 %';
  const p = v / total * 100;
  return p > 0 && p < 1 ? '< 1 %' : Math.round(p) + ' %';
}
function renderOverview() {
  const all = [...BL.values()].filter(b => b.status !== 'warten');
  const v = $('#view');
  if (!all.length) {
    v.innerHTML = `<div class="titlebar"><h1 class="left">Übersicht</h1></div>
      <section class="card empty"><p>Noch keine Belege</p><a class="btn" href="#/neu">${ICON.camera}Beleg erfassen</a></section>`;
    return;
  }
  const total = sum(all.map(betrag));
  const offen = all.filter(istOffen);
  const late = offen.filter(istUeberfaellig);
  const rows = S.cats.map(c => ({ c, v: sum(all.filter(b => b.kategorie === c.id).map(betrag)), n: all.filter(b => b.kategorie === c.id).length }));
  const ohne = all.filter(b => !catOf(b.kategorie));
  if (ohne.length) rows.push({ c: null, v: sum(ohne.map(betrag)), n: ohne.length });
  const shown = rows.filter(r => r.n);
  const max = Math.max(...shown.map(r => Math.abs(r.v)), 1);
  const months = new Map();
  all.forEach(b => { const k = tagOf(b).slice(0, 7); months.set(k, (months.get(k) || 0) + betrag(b)); });
  const dueOf = b => { const sk = skontoInfo(b); return (sk.active && sk.bis) || b.faellig || '9999'; };
  const openSorted = offen.slice().sort((a, c) => dueOf(a).localeCompare(dueOf(c)));
  const dueText = b => {
    const sk = skontoInfo(b);
    if (istUeberfaellig(b)) return '<span class="late">überfällig seit ' + fmtDate(b.faellig) + '</span>';
    if (sk.active && sk.bis) return '<span>Skonto bis ' + fmtDate(sk.bis) + ' · spart ' + eur(sk.saving) + '</span>';
    return '<span>' + (b.faellig ? 'fällig ' + fmtDate(b.faellig) : 'ohne Fälligkeit') + '</span>';
  };
  v.innerHTML = `
    <div class="titlebar"><h1 class="left">Übersicht</h1></div>
    <section class="hero"><small>Summe aller Belege</small><div class="hero-total">${eur0(total)}</div>
      <div class="pills"><span class="pill">${all.length} ${all.length === 1 ? 'Beleg' : 'Belege'}</span>
      ${offen.length ? `<span class="pill">Offen ${eur0(sum(offen.map(offenBetrag)))}</span>` : ''}
      ${late.length ? `<span class="pill">${late.length} überfällig</span>` : ''}</div></section>
    <section class="card"><div class="lhead"><h2>Nach Kategorie</h2></div>
      ${shown.map(r => `<button class="cbar" data-f="${r.c ? 'c:' + esc(r.c.id) : 'ohne'}">
        <div class="l"><i style="background:${catColor(r.c)}"></i><span class="n">${esc(r.c ? r.c.name : 'Ohne Kategorie')}</span>
        <span class="v">${eur0(r.v)}</span><span class="p">${sharePct(r.v, total)}</span></div>
        <div class="track"><div class="fill" style="width:${Math.max(0, r.v) / max * 100}%;background:${catColor(r.c)}"></div></div></button>`).join('')}
    </section>
    ${openSorted.length ? `<section class="card"><div class="lhead"><h2>Offen <small>${eur(sum(openSorted.map(offenBetrag)))}</small></h2></div>
      ${openSorted.map(b => `<a class="lrow" href="#/beleg/${esc(b.id)}"><span class="grow"><b>${esc(b.lieferant || 'Ohne Lieferant')}</b>
        ${dueText(b)}</span>
        <span class="v">${eur(betrag(b))}</span></a>`).join('')}</section>` : ''}
    <section class="card"><div class="lhead"><h2>Nach Monat</h2></div>
      ${[...months].sort((a, c) => c[0].localeCompare(a[0])).map(([k, val]) => `<div class="lrow"><span class="grow"><b>${esc(monthLabel(k))}</b></span><span class="v">${eur(val)}</span></div>`).join('')}
    </section>`;
  $$('.cbar', v).forEach(b => b.onclick = () => { ui.filter = b.dataset.f; ui.q = ''; ui.listScroll = 0; go('#/'); });
}

/* =============================================================
   Seite: Neuer Beleg (Fotos oder PDF)
   ============================================================= */
function newDraft() { return { art: null, pages: [], pdf: null, pdfName: '', pdfPages: 0, preview: null, thumb: null, busy: false }; }
function freeDraft() {
  if (!D) return;
  D.pages.forEach(p => URL.revokeObjectURL(p.url));
  if (D.preview) URL.revokeObjectURL(D.preview);
}
function renderCapture() {
  if (!D) D = newDraft();
  const v = $('#view');
  const hasKey = !!apiKey();
  let body;
  if (!D.art) {
    body = `<div class="pick">
      <button id="cam"><span class="ic">${ICON.camera}</span>Fotografieren</button>
      <button id="gal"><span class="ic">${ICON.image}</span>Aus Galerie</button>
      <button id="pdf"><span class="ic">${ICON.pdf}</span>PDF importieren</button></div>`;
  } else if (D.art === 'foto') {
    body = `<div class="pages">${D.pages.map((p, i) => `<div class="page"><img src="${p.url}" alt="" data-view="${i}"><span class="no">${i + 1}</span>
        <span class="acts"><button data-rot="${i}" aria-label="Drehen">${ICON.rotate}</button><button data-del="${i}" aria-label="Entfernen">${ICON.close}</button></span></div>`).join('')}
      <button class="page add" id="cam">${ICON.camera}Seite fotografieren</button>
      <button class="page add" id="gal">${ICON.image}Aus Galerie</button></div>`;
  } else {
    body = `<section class="card"><div class="pdfcard"><span class="thumb">${ICON.pdf}</span><span class="grow"><b>${esc(D.pdfName)}</b>
      <span>${D.pdfPages ? D.pdfPages + (D.pdfPages === 1 ? ' Seite' : ' Seiten') : 'PDF'}</span></span>
      <button class="iconbtn" id="pdel" aria-label="Entfernen">${ICON.close}</button></div>
      ${D.preview ? `<img class="pdfprev" src="${D.preview}" alt="" data-view="0">` : ''}</section>`;
  }
  const ready = D.art && (D.pages.length || D.pdf) && !D.busy;
  v.innerHTML = `
    <div class="titlebar"><button class="iconbtn" id="x" aria-label="Schließen">${ICON.close}</button><h1>Neuer Beleg</h1><span class="spacer"></span></div>
    ${D.busy ? '<section class="card pad"><div class="busy"><span class="spin"></span>Wird vorbereitet …</div></section>' : ''}
    ${body}
    ${D.art ? `<div class="savebar"><div class="inner">
      ${hasKey ? `<button class="btn" id="go" ${ready ? '' : 'disabled'}>${ICON.spark}Auslesen</button>`
      : `<button class="btn ghost" id="setkey">${ICON.key}API-Schlüssel</button><button class="btn" id="keep" ${ready ? '' : 'disabled'}>Speichern</button>`}
    </div></div>` : ''}
    <input type="file" id="fcam" accept="image/*" capture="environment" hidden>
    <input type="file" id="fgal" accept="image/*" multiple hidden>
    <input type="file" id="fpdf" accept="application/pdf,.pdf" hidden>`;
  $('#x').onclick = () => back('#/');
  const on = (sel, fn) => { const el = $(sel, v); if (el) el.onclick = fn; };
  on('#cam', () => $('#fcam').click());
  on('#gal', () => $('#fgal').click());
  on('#pdf', () => $('#fpdf').click());
  on('#pdel', () => { freeDraft(); D = newDraft(); renderCapture(); });
  on('#go', () => saveDraft(true));
  on('#keep', () => saveDraft(false));
  on('#setkey', () => keySheet());
  $('#fcam').onchange = e => { addImages([...e.target.files]); e.target.value = ''; };
  $('#fgal').onchange = e => { addImages([...e.target.files]); e.target.value = ''; };
  $('#fpdf').onchange = e => { const f = e.target.files[0]; e.target.value = ''; if (f) addPdf(f); };
  $$('[data-del]', v).forEach(b => b.onclick = () => {
    const i = +b.dataset.del; URL.revokeObjectURL(D.pages[i].url); D.pages.splice(i, 1);
    if (!D.pages.length) D.art = null;
    renderCapture();
  });
  $$('[data-rot]', v).forEach(b => b.onclick = async () => {
    const i = +b.dataset.rot, p = D.pages[i];
    try {
      const blob = await processImage(p.blob, 90);
      URL.revokeObjectURL(p.url);
      D.pages[i] = { blob, url: URL.createObjectURL(blob) };
      if (i === 0) D.thumb = await makeThumb(blob);
      renderCapture();
    } catch (e) { toast('Drehen nicht möglich'); }
  });
  $$('[data-view]', v).forEach(el => el.onclick = () => {
    const srcs = D.art === 'pdf' ? [D.preview] : D.pages.map(p => p.url);
    openViewer(srcs, +el.dataset.view);
  });
}
async function addImages(files) {
  if (!files.length || !D) return;
  if (D.art === 'pdf') { freeDraft(); D = newDraft(); }
  D.art = 'foto'; D.busy = true; renderCapture();
  let bad = 0;
  for (const f of files) {
    if (D.pages.length >= 20) { toast('Höchstens 20 Seiten je Beleg'); break; }
    try {
      const blob = await processImage(f);
      D.pages.push({ blob, url: URL.createObjectURL(blob) });
    } catch (e) { bad++; }
  }
  if (!D) return;
  if (D.pages.length && !D.thumb) { try { D.thumb = await makeThumb(D.pages[0].blob); } catch (e) { } }
  if (!D.pages.length) D.art = null;
  D.busy = false;
  if (bad) toast('Bildformat wird nicht unterstützt');
  if (ui.view === 'neu') renderCapture();
}
async function addPdf(file) {
  if (!D) return;
  if (file.size > 24 * 1024 * 1024) { toast('PDF ist zu groß (höchstens 24 MB)'); return; }
  freeDraft(); D = newDraft();
  D.art = 'pdf'; D.busy = true; renderCapture();
  const blob = new Blob([await file.arrayBuffer()], { type: 'application/pdf' });
  D.pdf = blob; D.pdfName = file.name || 'Beleg.pdf';
  try {
    const doc = await openPdf(blob);
    D.pdfPages = doc.numPages;
    const prev = await renderPdfPage(doc, 1, 900);
    D.preview = URL.createObjectURL(prev);
    D.thumb = await makeThumb(prev);
    closePdf(doc);
  } catch (e) {
    if (e && e.name === 'PasswordException') { toast('PDF ist passwortgeschützt'); D = newDraft(); if (ui.view === 'neu') renderCapture(); return; }
    // Vorschau nicht möglich – das PDF selbst kann trotzdem ausgelesen werden
  }
  if (!D) return;
  D.busy = false;
  if (ui.view === 'neu') renderCapture();
}
async function saveDraft(doExtract) {
  if (!D || D.busy) return;
  const b = blankBeleg(D.art);
  const files = D.art === 'pdf'
    ? [{ id: uid(), beleg: b.id, idx: 0, type: 'application/pdf', name: D.pdfName, blob: D.pdf }]
    : D.pages.map((p, i) => ({ id: uid(), beleg: b.id, idx: i, type: 'image/jpeg', name: 'seite-' + (i + 1) + '.jpg', blob: p.blob }));
  if (!files.length) return;
  b.dateien = files.map(f => f.id);
  b.thumb = D.thumb;
  try {
    await dbWrite((sb, sf) => { sb.put(b); files.forEach(f => sf.put(f)); });
  } catch (e) { toast(storageError(e)); return; }
  BL.set(b.id, b);
  freeDraft(); D = null;
  go('#/beleg/' + b.id);
  if (doExtract) extract(b.id);
}

/* =============================================================
   Seite: Beleg prüfen / bearbeiten
   ============================================================= */
let pageCache = { id: null, key: '', urls: [], loading: null };
function freePages() { pageCache.urls.forEach(u => URL.revokeObjectURL(u)); pageCache = { id: null, key: '', urls: [], loading: null }; }
function pageUrls(b) {
  const key = b.dateien.join();
  if (pageCache.id === b.id && pageCache.key === key) return pageCache.loading || Promise.resolve(pageCache.urls);
  freePages();
  const pc = pageCache = { id: b.id, key, urls: [], loading: null };
  pc.loading = (async () => {
    const files = await filesOf(b);
    let blobs = [];
    if (b.art === 'pdf' && files[0]) {
      const doc = await openPdf(files[0].blob);
      try { for (let n = 1; n <= Math.min(doc.numPages, 30); n++) blobs.push(await renderPdfPage(doc, n, 1600)); }
      finally { closePdf(doc); }
    } else blobs = files.map(f => f.blob);
    pc.urls = blobs.map(x => URL.createObjectURL(x));
    pc.loading = null;
    if (pageCache !== pc) { pc.urls.forEach(u => URL.revokeObjectURL(u)); return []; }
    return pc.urls;
  })();
  pc.loading.catch(() => { if (pageCache === pc) pageCache = { id: null, key: '', urls: [], loading: null }; });
  return pc.loading;
}
const normNr = s => (s || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
const normName = s => (s || '').toLowerCase().replace(/\b(gmbh|gesmbh|ges\.m\.b\.h\.?|mbh|co|kg|og|ohg|ag|e\.u\.|eu|ek|e\.k\.|ug)\b/g, '').replace(/[^a-z0-9äöüß]/g, '');
const sameName = (a, b) => !!a && !!b && (a === b || (a.length >= 4 && b.length >= 4 && (a.startsWith(b) || b.startsWith(a))));
function findDups(w) {
  const nr = normNr(w.nr), lf = normName(w.lieferant);
  if (!lf) return [];
  return [...BL.values()].filter(o => o.id !== w.id && o.status !== 'warten' && sameName(lf, normName(o.lieferant)) &&
    (nr ? normNr(o.nr) === nr : (w.datum && o.datum === w.datum && isNum(w.brutto) && o.brutto === w.brutto)));
}
function plaus(w) {
  const rows = w.steuer.filter(s => isNum(s.netto) || isNum(s.ust));
  const sn = round2(sum(rows.map(r => r.netto))), su = round2(sum(rows.map(r => r.ust)));
  const bad = new Set();
  w.steuer.forEach((s, i) => {
    if (isNum(s.satz) && isNum(s.netto) && isNum(s.ust) && Math.abs(s.netto * s.satz / 100 - s.ust) > Math.max(0.05, Math.abs(s.netto) * 0.0005)) bad.add(i);
  });
  let ok = null, diff = 0;
  if (rows.length && isNum(w.brutto)) { diff = round2(sn + su - w.brutto); ok = Math.abs(diff) <= 0.02 && !bad.size; }
  return { sn, su, bad, ok, diff };
}
function renderDetail(id, fresh = false) {
  const b0 = BL.get(id);
  if (!b0) { go('#/'); return; }
  if (fresh || !DS || DS.id !== id) DS = { id, w: clone(b0), dirty: false, manual: DS && DS.id === id ? DS.manual : false, posOpen: DS && DS.id === id ? DS.posOpen : false };
  const w = DS.w;
  const busy = running.has(id);
  const showForm = !busy && (w.status !== 'warten' || DS.manual);
  const v = $('#view');
  const title = w.status === 'ok' ? 'Beleg' : 'Beleg prüfen';
  let notice = '';
  if (busy) notice = '<section class="card pad"><div class="busy"><span class="spin"></span>Wird ausgelesen …</div></section>';
  else if (w.status === 'warten' && !DS.manual) {
    const hasKey = !!apiKey();
    notice = `<div class="notice ${w.fehler && !w.netzfehler ? 'err' : 'info'}"><b>${w.fehler ? esc(w.fehler) : 'Noch nicht ausgelesen'}</b>
      <div class="row">${hasKey ? `<button class="btn" id="ex">${ICON.spark}Auslesen</button>` : `<button class="btn" id="setkey">${ICON.key}API-Schlüssel</button>`}
      <button class="btn ghost" id="manual">Selbst ausfüllen</button></div></div>`;
  }
  v.innerHTML = `
    <div class="titlebar"><button class="iconbtn" id="bk" aria-label="Zurück">${ICON.back}</button><h1>${title}</h1><span class="spacer"></span></div>
    <div class="strip" id="strip">${(w.dateien || []).map((_, i) => `<button data-p="${i}"><span class="ph"><span class="spin"></span></span></button>`).join('')}</div>
    ${notice}
    <div id="dups"></div>
    ${showForm ? formHTML(w) : ''}
    ${showForm ? `<div class="savebar"><div class="inner"><button class="btn" id="save">Speichern</button></div></div>` : ''}`;
  $('#bk').onclick = () => back('#/');
  const on = (sel, fn) => { const el = $(sel, v); if (el) el.onclick = fn; };
  on('#ex', () => extract(id));
  on('#setkey', () => keySheet());
  on('#manual', () => { DS.manual = true; renderDetail(id); });
  loadStrip(b0);
  if (showForm) bindForm(v);
}
async function loadStrip(b) {
  let urls = [];
  try { urls = await pageUrls(b); } catch (e) { }
  const strip = $('#strip');
  if (!strip || !DS || DS.id !== b.id) return;
  if (!urls.length) { $$('button', strip).forEach(x => { x.innerHTML = `<span class="ph">${b.art === 'pdf' ? ICON.pdf : ICON.image}</span>`; }); return; }
  strip.innerHTML = urls.map((u, i) => `<button data-p="${i}"><img src="${u}" alt="Seite ${i + 1}">${urls.length > 1 ? `<span class="no">${i + 1}</span>` : ''}</button>`).join('');
  $$('button', strip).forEach(x => x.onclick = () => openViewer(urls, +x.dataset.p));
}
function formHTML(w) {
  const un = f => (w.unsicher || []).includes(f) ? ' unsure' : '';
  const catOpts = `<option value="">–</option>` + S.cats.map(c => `<option value="${esc(c.id)}" ${w.kategorie === c.id ? 'selected' : ''}>${esc(c.name)}</option>`).join('');
  const lands = COUNTRIES.slice();
  if (w.land && !lands.some(l => l[0] === w.land)) lands.push([w.land, w.land]);
  const landOpts = `<option value="">–</option>` + lands.map(([k, n]) => `<option value="${k}" ${w.land === k ? 'selected' : ''}>${esc(n)}</option>`).join('');
  if (!w.steuer.length) w.steuer.push({ satz: null, netto: null, ust: null });
  const m = w.modell ? modelOf(w.modell).name : null;
  return `
    ${w.waehrung && w.waehrung !== 'EUR' ? `<div class="notice warn"><b>Währung ${esc(w.waehrung)}</b>Beträge sind nicht in Euro umgerechnet.</div>` : ''}
    <section class="card form">
      <div class="f"><label for="f-lieferant">Lieferant</label><input id="f-lieferant" data-k="lieferant" class="${un('lieferant')}" value="${esc(w.lieferant)}" autocomplete="off"></div>
      <div class="grid2">
        <div class="f"><label for="f-nr">Rechnungsnr.</label><input id="f-nr" data-k="nr" class="${un('rechnungsnummer')}" value="${esc(w.nr)}" autocomplete="off"></div>
        <div class="f"><label for="f-land">Land</label><select id="f-land" data-k="land" class="${un('land')}">${landOpts}</select></div>
        <div class="f"><label for="f-datum">Datum</label><input id="f-datum" type="date" data-k="datum" class="${un('rechnungsdatum')}" value="${esc(w.datum)}"></div>
        <div class="f"><label for="f-faellig">Fällig am</label><input id="f-faellig" type="date" data-k="faellig" class="${un('faelligkeitsdatum')}" value="${esc(w.faellig)}"></div>
      </div>
      <div class="f"><label for="f-kat">Kategorie</label><select id="f-kat" data-k="kategorie" class="${un('kategorie')}">${catOpts}</select></div>
      <div class="f"><span class="flabel">Status</span><div class="seg" id="paid">
        <button data-v="0" class="${w.bezahlt ? '' : 'on'}">Offen</button><button data-v="1" class="${w.bezahlt ? 'on' : ''}">Bezahlt</button></div></div>
      <div class="grid2" id="paidx" ${w.bezahlt ? '' : 'hidden'}>
        <div class="f"><label for="f-bam">Bezahlt am</label><input id="f-bam" type="date" data-k="bezahltAm" value="${esc(w.bezahltAm)}"></div>
        <div class="f"><label for="f-gez">Gezahlt</label><input id="f-gez" data-k="gezahlt" data-money class="num" inputmode="decimal" value="${fmtIn(w.gezahlt)}" placeholder="${fmtIn(offenBetrag(w))}"></div>
      </div>
    </section>
    <section class="card">
      <div class="lhead"><h2>Beträge</h2></div>
      <div class="tax" id="tax">${taxHTML(w)}</div>
      <div class="amounts grid2">
        <div class="f"><label for="f-brutto">Brutto</label><input id="f-brutto" data-k="brutto" data-money class="num${un('brutto')}" inputmode="decimal" value="${fmtIn(w.brutto)}"></div>
        <div class="f"><label for="f-zahl">Zu zahlen</label><input id="f-zahl" data-k="zahlbetrag" data-money class="num${un('zahlbetrag')}" inputmode="decimal" value="${fmtIn(w.zahlbetrag)}"></div>
      </div>
      <div id="check"></div>
    </section>
    <section class="card" id="pay">${payHTML(w)}</section>
    <section class="card" id="pos">${posHTML(w)}</section>
    <section class="card form"><div class="f"><label for="f-notiz">Notiz</label><textarea id="f-notiz" data-k="notiz" rows="3">${esc(w.notiz)}</textarea></div></section>
    ${m ? `<p class="meta">Ausgelesen mit ${esc(m)} am ${fmtDate(w.ausgelesen.slice(0, 10))}${w.kosten ? ' · ≈ ' + fmtNum(w.kosten * 100, 1) + ' Cent' : ''}</p>` : ''}
    ${apiKey() ? `<button class="btn ghost block" id="redo" style="margin-top:8px">${ICON.redo}Neu auslesen</button>` : ''}
    <button class="linkdanger" id="del">Beleg löschen</button>`;
}
function taxHTML(w) {
  const un = f => (w.unsicher || []).includes(f) ? ' unsure' : '';
  const p = plaus(w);
  return `<div class="trow head"><span>Satz %</span><span>Netto</span><span>USt</span><span></span></div>
    ${w.steuer.map((s, i) => `<div class="trow${p.bad.has(i) ? ' bad' : ''}" data-row="${i}">
      <input class="tin num${un('steuer')}" data-k="st.${i}.satz" inputmode="decimal" value="${fmtRate(s.satz)}" aria-label="Steuersatz">
      <input class="tin num${un('steuer')}${un('netto')}" data-k="st.${i}.netto" data-money inputmode="decimal" value="${fmtIn(s.netto)}" aria-label="Netto">
      <input class="tin num${un('steuer')}${un('ust')}" data-k="st.${i}.ust" data-money inputmode="decimal" value="${fmtIn(s.ust)}" aria-label="Umsatzsteuer">
      <button class="x" data-delst="${i}" aria-label="Zeile entfernen">${ICON.close}</button></div>`).join('')}
    <button class="linkbtn" id="addst">${ICON.plus}Steuersatz</button>
    ${w.steuer.length > 1 ? `<div class="trow sum"><span style="text-align:left;padding:0">Summe</span><span id="sn">${eur(p.sn)}</span><span id="su">${eur(p.su)}</span><span></span></div>` : ''}`;
}
function payHTML(w) {
  const un = f => (w.unsicher || []).includes(f) ? ' unsure' : '';
  const hasData = w.iban || w.empfaenger || w.referenz || isNum(w.skontoProz);
  if (w.bezahlt && !hasData && !DS.payOpen) {
    return `<button class="ptoggle" id="paytog"><span class="grow">Zahlungsdaten</span>${ICON.go}</button>`;
  }
  const sk = skontoInfo(w);
  return `<div class="form">
    <div class="f" style="margin-top:2px"><h2 style="font-size:var(--fs-md);font-weight:var(--w-semi)">Zahlung</h2></div>
    <div class="f"><label for="f-empf">Empfänger</label><input id="f-empf" data-k="empfaenger" value="${esc(w.empfaenger)}" placeholder="${esc(w.lieferant)}" autocomplete="off"></div>
    <div class="f"><label for="f-iban">IBAN</label><input id="f-iban" data-k="iban" class="iban${un('iban')}" value="${esc(fmtIban(w.iban))}" autocomplete="off" autocapitalize="characters" spellcheck="false">
      <div class="ferr" id="iban-err" ${!w.iban || ibanOk(w.iban) ? 'hidden' : ''}>IBAN ungültig – bitte mit dem Beleg vergleichen</div></div>
    <div class="grid2">
      <div class="f"><label for="f-bic">BIC</label><input id="f-bic" data-k="bic" value="${esc(w.bic)}" autocomplete="off" autocapitalize="characters" spellcheck="false"></div>
      <div class="f"><label for="f-ref">Zahlungsreferenz</label><input id="f-ref" data-k="referenz" value="${esc(w.referenz)}" placeholder="${w.nr ? esc('Rechnung ' + w.nr) : ''}" autocomplete="off"></div>
      <div class="f"><label for="f-skp">Skonto %</label><input id="f-skp" data-k="skontoProz" class="num${un('skonto')}" inputmode="decimal" value="${fmtRate(w.skontoProz)}"></div>
      <div class="f"><label for="f-skb">Skonto bis</label><input id="f-skb" type="date" data-k="skontoBis" class="${un('skonto')}" value="${esc(w.skontoBis)}"></div>
    </div>
    <div class="f" id="skbx" ${isNum(w.skontoProz) && w.skontoProz > 0 ? '' : 'hidden'}><label for="f-skbt">Mit Skonto zu zahlen</label>
      <input id="f-skbt" data-k="skontoBetrag" data-money class="num${un('skonto')}" inputmode="decimal" value="${fmtIn(w.skontoBetrag)}" placeholder="${sk.amount ? fmtIn(sk.amount) : ''}"></div>
    <div id="ibanwarn"></div>
    <button class="btn block" id="paybtn" style="margin:4px 0 16px" ${w.bezahlt ? 'hidden' : ''}>${ICON.euro}Bezahlen</button>
  </div>`;
}
function refreshIbanWarn() {
  const el = $('#ibanwarn');
  if (!el) return;
  const o = otherIbans(DS.w);
  el.innerHTML = o.length ? `<div class="notice err"><b>Andere IBAN als bisher</b>Frühere Rechnungen: ${o.map(x => esc(fmtIban(x))).join(', ')}. Vor dem Bezahlen beim Lieferanten telefonisch nachfragen.</div>` : '';
}
function posHTML(w) {
  const un = (w.unsicher || []).includes('positionen');
  const n = w.positionen.length;
  return `<button class="ptoggle${DS.posOpen ? ' open' : ''}" id="ptog"><span class="grow">Positionen</span>${un ? '<span class="badge" style="background:var(--warn-soft);color:var(--warn)">prüfen</span>' : ''}<small>${n || ''}</small>${ICON.go}</button>
    ${DS.posOpen ? `<div class="plist">${w.positionen.map((p, i) => `<div class="prow">
      <div class="top"><input class="tin" data-k="pos.${i}.text" value="${esc(p.text)}" aria-label="Bezeichnung"></div>
      <div class="bot"><input class="tin" data-k="pos.${i}.menge" value="${esc(p.menge)}" placeholder="Menge" aria-label="Menge">
      <input class="tin num" data-k="pos.${i}.betrag" data-money inputmode="decimal" value="${fmtIn(p.betrag)}" placeholder="Betrag" aria-label="Betrag">
      <button class="x" data-delpos="${i}" aria-label="Position entfernen">${ICON.close}</button></div></div>`).join('')}
      <button class="linkbtn" id="addpos">${ICON.plus}Position</button></div>` : ''}`;
}
function markDirty() { if (DS) DS.dirty = true; }
function refreshCheck() {
  const w = DS.w, p = plaus(w), el = $('#check');
  if (!el) return;
  $$('#tax .trow[data-row]').forEach(r => r.classList.toggle('bad', p.bad.has(+r.dataset.row)));
  if ($('#sn')) { $('#sn').textContent = eur(p.sn); $('#su').textContent = eur(p.su); }
  if (p.ok === true) el.innerHTML = `<div class="check ok">${ICON.ok}<span>Netto + USt = Brutto</span></div>`;
  else if (p.ok === false) {
    const parts = [];
    if (p.bad.size) parts.push('USt passt nicht zum Steuersatz');
    if (Math.abs(p.diff) > 0.02) parts.push(`Netto + USt = ${eur(round2(p.sn + p.su))}, Brutto weicht um ${eur(Math.abs(p.diff))} ab`);
    el.innerHTML = `<div class="check bad">${ICON.warn}<span>${parts.join(' · ')}</span></div>`;
  } else el.innerHTML = '';
}
function refreshDups() {
  const el = $('#dups');
  if (!el) return;
  const d = findDups(DS.w);
  el.innerHTML = d.length ? `<div class="notice err"><b>Bereits erfasst?</b>${d.map(o => `<button class="dup" data-id="${esc(o.id)}">${esc(o.lieferant)}${o.nr ? ' · Nr. ' + esc(o.nr) : ''} · ${fmtDate(o.datum)} · ${eur(betrag(o))}</button>`).join('')}</div>` : '';
  $$('.dup', el).forEach(b => b.onclick = () => { if (DS.dirty && !confirm('Änderungen verwerfen?')) return; DS.dirty = false; nav('#/beleg/' + b.dataset.id); });
}
const UNSURE_OF = { lieferant: ['lieferant'], nr: ['rechnungsnummer'], land: ['land'], datum: ['rechnungsdatum'], faellig: ['faelligkeitsdatum'], kategorie: ['kategorie'], brutto: ['brutto'], zahlbetrag: ['zahlbetrag'], iban: ['iban'], skontoProz: ['skonto'], skontoBis: ['skonto'], skontoBetrag: ['skonto'] };
function setVal(k, raw) {
  const w = DS.w, parts = k.split('.');
  if (parts[0] === 'st') { const r = w.steuer[+parts[1]]; if (r) r[parts[2]] = parseNum(raw); }
  else if (parts[0] === 'pos') { const r = w.positionen[+parts[1]]; if (r) r[parts[2]] = parts[2] === 'betrag' ? parseNum(raw) : raw; }
  else if (k === 'brutto' || k === 'zahlbetrag' || k === 'skontoProz' || k === 'skontoBetrag' || k === 'gezahlt') w[k] = parseNum(raw);
  else if (k === 'iban') w.iban = normIban(raw);
  else if (k === 'kategorie') w.kategorie = raw || null;
  else w[k] = raw;
}
function clearUnsure(el, k) {
  if (!el.classList.contains('unsure')) return;
  el.classList.remove('unsure');
  if (!k.startsWith('st.')) DS.w.unsicher = (DS.w.unsicher || []).filter(f => !(UNSURE_OF[k] || []).includes(f));
}
/* Eingaben im Beleg-Formular – einmal am #view registriert, gilt für jedes Rendern */
const formField = e => ui.view === 'beleg' && DS && e.target.dataset && e.target.dataset.k ? e.target : null;
let dupT;
function onFormInput(e) {
  const el = formField(e); if (!el) return;
  const k = el.dataset.k;
  setVal(k, el.value); markDirty(); clearUnsure(el, k);
  if (el.hasAttribute('data-money') || k.startsWith('st.')) { el.classList.toggle('neg', Number.isNaN(parseNum(el.value))); refreshCheck(); }
  if (k === 'lieferant' || k === 'nr' || k === 'datum') { clearTimeout(dupT); dupT = setTimeout(refreshDups, 300); }
  if (k === 'iban' || k === 'lieferant') {
    const bad = !!DS.w.iban && !ibanOk(DS.w.iban);
    const err = $('#iban-err');
    // Fehler erst zeigen, wenn die IBAN vollständig sein könnte
    if (err) err.hidden = !bad || DS.w.iban.length < 15;
    clearTimeout(onFormInput.t); onFormInput.t = setTimeout(refreshIbanWarn, 300);
  }
  if (k === 'skontoProz' || k === 'zahlbetrag' || k === 'brutto') {
    const box = $('#skbx'), sk = skontoInfo(DS.w);
    if (box) box.hidden = !(isNum(DS.w.skontoProz) && DS.w.skontoProz > 0);
    const inp = $('#f-skbt');
    if (inp) inp.placeholder = sk.amount ? fmtIn(sk.amount) : '';
  }
}
function onFormChange(e) {
  const el = formField(e); if (!el) return;
  setVal(el.dataset.k, el.value); markDirty(); clearUnsure(el, el.dataset.k);
  if (el.tagName === 'SELECT' || el.type === 'date') refreshDups();
}
function onFormBlur(e) {
  const el = formField(e); if (!el) return;
  if (el.dataset.k === 'iban') { el.value = fmtIban(el.value); const err = $('#iban-err'); if (err) err.hidden = !DS.w.iban || ibanOk(DS.w.iban); return; }
  if (!el.hasAttribute('data-money')) return;
  const n = parseNum(el.value);
  if (isNum(n)) el.value = fmtIn(n);
}
function bindForm(v) {
  const w = DS.w;
  $$('#paid button', v).forEach(b => b.onclick = () => {
    w.bezahlt = b.dataset.v === '1'; markDirty();
    $$('#paid button', v).forEach(x => x.classList.toggle('on', x === b));
    if (w.bezahlt && !w.bezahltAm) { w.bezahltAm = today(); $('#f-bam').value = w.bezahltAm; }
    $('#paidx').hidden = !w.bezahlt;
    const pb = $('#paybtn'); if (pb) pb.hidden = w.bezahlt;
  });
  const rebindPay = () => {
    $('#pay').innerHTML = payHTML(w);
    const t = $('#paytog'); if (t) t.onclick = () => { DS.payOpen = true; rebindPay(); };
    const pb = $('#paybtn'); if (pb) pb.onclick = paySheet;
    refreshIbanWarn();
  };
  const rebindTax = () => {
    $('#tax').innerHTML = taxHTML(w);
    $('#addst').onclick = () => { w.steuer.push({ satz: null, netto: null, ust: null }); markDirty(); rebindTax(); };
    $$('[data-delst]', v).forEach(b => b.onclick = () => { w.steuer.splice(+b.dataset.delst, 1); if (!w.steuer.length) w.steuer.push({ satz: null, netto: null, ust: null }); markDirty(); rebindTax(); });
    refreshCheck();
  };
  const rebindPos = () => {
    $('#pos').innerHTML = posHTML(w);
    $('#ptog').onclick = () => { DS.posOpen = !DS.posOpen; rebindPos(); };
    const add = $('#addpos');
    if (add) add.onclick = () => { w.positionen.push({ text: '', menge: '', betrag: null }); markDirty(); rebindPos(); const ins = $$('#pos [data-k$=".text"]'); if (ins.length) ins[ins.length - 1].focus(); };
    $$('[data-delpos]', v).forEach(b => b.onclick = () => { w.positionen.splice(+b.dataset.delpos, 1); markDirty(); rebindPos(); });
  };
  rebindTax(); rebindPos(); rebindPay(); refreshDups();
  $('#save').onclick = saveDetail;
  $('#del').onclick = deleteBeleg;
  const redo = $('#redo');
  if (redo) redo.onclick = redoSheet;
}
async function saveDetail() {
  const w = DS.w;
  const bad = [w.brutto, w.zahlbetrag, w.skontoProz, w.skontoBetrag, w.gezahlt, ...w.steuer.flatMap(s => [s.satz, s.netto, s.ust]), ...w.positionen.map(p => p.betrag)].some(x => Number.isNaN(x));
  if (bad) { toast('Bitte die rot markierten Beträge prüfen'); return; }
  w.lieferant = w.lieferant.trim(); w.nr = w.nr.trim(); w.notiz = w.notiz.trim();
  w.steuer = w.steuer.filter(s => isNum(s.netto) || isNum(s.ust));
  w.positionen = w.positionen.map(p => ({ text: p.text.trim(), menge: (p.menge || '').trim(), betrag: p.betrag })).filter(p => p.text || isNum(p.betrag));
  if (w.steuer.length) { const p = plaus(w); w.netto = p.sn; w.ust = p.su; }
  if (!isNum(w.zahlbetrag) && isNum(w.brutto)) w.zahlbetrag = w.brutto;
  w.empfaenger = w.empfaenger.trim(); w.iban = normIban(w.iban); w.bic = w.bic.replace(/\s/g, '').toUpperCase(); w.referenz = w.referenz.trim();
  if (!(w.skontoProz > 0)) { w.skontoProz = null; w.skontoBis = ''; w.skontoBetrag = null; }
  if (w.bezahlt) { if (!w.bezahltAm) w.bezahltAm = today(); }
  else { w.bezahltAm = ''; w.gezahlt = null; }
  w.status = 'ok'; w.unsicher = []; w.fehler = null; w.netzfehler = false;
  w.updated = new Date().toISOString();
  const cur = BL.get(w.id);
  if (!cur) { toast('Beleg wurde gelöscht'); return; }
  try { await putBeleg(w); } catch (e) { toast(storageError(e)); return; }
  DS.dirty = false;
  toast('Gespeichert');
  back('#/');
}
async function deleteBeleg() {
  if (!confirm('Beleg löschen? Das kann nicht rückgängig gemacht werden.')) return;
  const b = BL.get(DS.id);
  if (!b) return;
  try { await dbWrite((sb, sf) => { sb.delete(b.id); (b.dateien || []).forEach(f => sf.delete(f)); }); }
  catch (e) { toast('Löschen fehlgeschlagen'); return; }
  BL.delete(b.id);
  DS.dirty = false;
  toast('Beleg gelöscht');
  back('#/');
}
function redoSheet() {
  let sel = S.model;
  const draw = () => {
    const sh = openSheet(`<h3>Neu auslesen</h3>
      ${MODELS.map(m => `<button class="opt${m.id === sel ? ' on' : ''}" data-m="${m.id}"><span class="grow"><b>${esc(m.name)}</b><span>${esc(m.hint)}</span></span><span class="radio"></span></button>`).join('')}
      <div class="sheet-actions"><button class="btn block" id="run">${ICON.spark}Auslesen</button></div>`);
    $$('.opt', sh).forEach(b => b.onclick = () => { sel = b.dataset.m; draw(); });
    $('#run', sh).onclick = () => {
      if (DS.dirty && !confirm('Deine Änderungen werden überschrieben. Fortfahren?')) return;
      DS.dirty = false;
      closeSheet();
      extract(DS.id, sel);
    };
  };
  draw();
}

/* ---------------- Bezahlen: „Zahlen mit Code“ (EPC-QR) für George ----------------
   Aufbau nach EPC069-12, Version 002 (BIC optional), Zeichensatz UTF-8. */
function epcPayload(w, amount) {
  const clean = (t, n) => String(t || '').replace(/[\r\n]+/g, ' ').trim().slice(0, n);
  const ref = clean(w.referenz, 140).replace(/\s/g, '').toUpperCase();
  const rf = /^RF\d{2}[A-Z0-9]{1,21}$/.test(ref); // strukturierte Referenz nach ISO 11649
  const text = rf ? '' : clean(w.referenz || (w.nr ? 'Rechnung ' + w.nr : ''), 140);
  const f = ['BCD', '002', '1', 'SCT', clean(w.bic, 11).replace(/\s/g, '').toUpperCase(), clean(w.empfaenger || w.lieferant, 70),
    normIban(w.iban), 'EUR' + amount.toFixed(2), '', rf ? ref : '', text];
  while (f[f.length - 1] === '') f.pop();
  return f.join('\n');
}
let qrLoad = null;
function loadQR() {
  if (window.qrcode) return Promise.resolve();
  if (!qrLoad) qrLoad = new Promise((res, rej) => {
    const sc = document.createElement('script');
    sc.src = 'lib/qrcode.js';
    sc.onload = res;
    sc.onerror = () => { qrLoad = null; rej(new Error('qr')); };
    document.head.appendChild(sc);
  });
  return qrLoad;
}
/* QR-Code als Bild; darunter Empfänger und Betrag, damit man das Bild im Ordner wiedererkennt */
function qrCanvas(payload, line1, line2) {
  qrcode.stringToBytes = qrcode.stringToBytesFuncs['UTF-8'];
  const qr = qrcode(0, 'M');
  qr.addData(payload, 'Byte');
  qr.make();
  const n = qr.getModuleCount(), cell = 10, margin = 4 * cell, size = n * cell + 2 * margin, capH = 84;
  const c = document.createElement('canvas');
  c.width = size; c.height = size + capH;
  const x = c.getContext('2d');
  x.fillStyle = '#fff'; x.fillRect(0, 0, c.width, c.height);
  x.fillStyle = '#000';
  for (let r = 0; r < n; r++) for (let q = 0; q < n; q++) if (qr.isDark(r, q)) x.fillRect(margin + q * cell, margin + r * cell, cell, cell);
  x.textAlign = 'center'; x.fillStyle = '#0F172A';
  x.font = '600 24px Inter, sans-serif';
  x.fillText(line1.length > 34 ? line1.slice(0, 33) + '…' : line1, size / 2, size + 22);
  x.font = '700 30px Inter, sans-serif';
  x.fillText(line2, size / 2, size + 62);
  return c;
}
async function copyText(t, what) {
  try { await navigator.clipboard.writeText(t); toast(what + ' kopiert'); }
  catch (e) { toast('Kopieren nicht möglich'); }
}
/* Sheet schließen und erst nach dem Zurück-Schritt weitermachen */
function closeSheetThen(fn) {
  if (!sheetOpen) { fn(); return; }
  removeSheet();
  let done = false;
  const go2 = () => { if (!done) { done = true; setTimeout(fn, 0); } };
  window.addEventListener('popstate', go2, { once: true });
  setTimeout(go2, 600);
  history.back();
}
async function paySheet() {
  const w = DS.w;
  if (!ibanOk(w.iban)) { toast(w.iban ? 'IBAN ungültig – bitte prüfen' : 'IBAN fehlt'); const i = $('#f-iban'); if (i) i.focus(); return; }
  if (w.waehrung && w.waehrung !== 'EUR') { toast('Nur Zahlungen in Euro möglich'); return; }
  if (!(w.empfaenger || w.lieferant).trim()) { toast('Empfänger fehlt'); return; }
  const full = offenBetrag(w);
  if (!(full > 0)) { toast('Kein Betrag zu zahlen'); return; }
  try { await loadQR(); } catch (e) { toast('QR-Code nicht verfügbar'); return; }
  const sk = skontoInfo(w);
  let useSk = !!sk.active;
  const warn = otherIbans(w);
  const draw = () => {
    const amount = useSk ? sk.amount : full;
    const name = w.empfaenger || w.lieferant;
    const ref = w.referenz || (w.nr ? 'Rechnung ' + w.nr : '');
    const canvas = qrCanvas(epcPayload(w, amount), name, eur(amount));
    const sh = openSheet(`<h3>Bezahlen</h3>
      ${warn.length ? `<div class="notice err"><b>Andere IBAN als bisher</b>Vor dem Bezahlen beim Lieferanten telefonisch nachfragen.</div>` : ''}
      ${sk.active ? `<div class="seg" id="skseg"><button data-s="1" class="${useSk ? 'on' : ''}">Mit Skonto</button><button data-s="0" class="${useSk ? '' : 'on'}">Ohne Skonto</button></div>` : ''}
      <div class="qrbox"><img src="${canvas.toDataURL('image/png')}" alt="QR-Code zum Bezahlen"></div>
      <div class="plines">
        <div class="pl"><span class="k">Betrag</span><span class="v">${eur(amount)}${useSk && sk.bis ? `<small>Skonto bis ${fmtDate(sk.bis)} · spart ${eur(sk.saving)}</small>` : ''}</span><button data-copy="${amount.toFixed(2).replace('.', ',')}" data-what="Betrag" aria-label="Betrag kopieren">${ICON.copy}</button></div>
        <div class="pl"><span class="k">Empfänger</span><span class="v">${esc(name)}</span><button data-copy="${esc(name)}" data-what="Empfänger" aria-label="Empfänger kopieren">${ICON.copy}</button></div>
        <div class="pl"><span class="k">IBAN</span><span class="v iban">${esc(fmtIban(w.iban))}</span><button data-copy="${esc(normIban(w.iban))}" data-what="IBAN" aria-label="IBAN kopieren">${ICON.copy}</button></div>
        ${ref ? `<div class="pl"><span class="k">Referenz</span><span class="v">${esc(ref)}</span><button data-copy="${esc(ref)}" data-what="Referenz" aria-label="Referenz kopieren">${ICON.copy}</button></div>` : ''}
      </div>
      <div class="sheet-actions">
        <button class="btn block" id="qrsave">${ICON.save}QR-Code speichern</button>
        <button class="btn ghost block" id="george">${ICON.bank}George öffnen</button>
        <button class="btn ghost block" id="markpaid">${ICON.ok}Als bezahlt markieren</button>
      </div>`);
    $$('#skseg button', sh).forEach(b => b.onclick = () => { useSk = b.dataset.s === '1'; draw(); });
    $$('[data-copy]', sh).forEach(b => b.onclick = () => copyText(b.dataset.copy, b.dataset.what));
    $('#qrsave', sh).onclick = () => canvas.toBlob(blob => {
      const slug = name.toLowerCase().replace(/[^a-z0-9äöüß]+/g, '-').replace(/^-|-$/g, '').slice(0, 30) || 'zahlung';
      download('zahlung-' + slug + '-' + amount.toFixed(2).replace('.', '-') + '.png', blob);
      toast('Gespeichert – in George: QR-Code scannen, aus Ordner');
    }, 'image/png');
    $('#george', sh).onclick = () => { location.href = 'intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=at.erstebank.george;end'; };
    $('#markpaid', sh).onclick = () => {
      w.bezahlt = true; w.bezahltAm = today(); w.gezahlt = amount;
      markDirty();
      closeSheetThen(saveDetail);
    };
  };
  draw();
}

/* =============================================================
   Seite: Einstellungen
   ============================================================= */
function maskKey(k) { return k ? k.slice(0, 7) + '…' + k.slice(-4) : ''; }
async function renderSettings() {
  const key = apiKey();
  const m = modelOf(S.model);
  const theme = S.theme || 'system';
  const lb = S.lastBackup ? fmtDate(S.lastBackup.slice(0, 10)) : 'noch nie';
  $('#view').innerHTML = `
    <div class="titlebar"><h1 class="left">Einstellungen</h1></div>
    <div class="group-title">Auslesen</div>
    <section class="card">
      <button class="item" id="key">${ICON.key}<span class="grow">API-Schlüssel</span><span class="sub${key ? '' : ' bad'}">${key ? esc(maskKey(key)) : 'fehlt'}</span>${ICON.go}</button>
      <button class="item" id="model">${ICON.spark}<span class="grow">Modell</span><span class="sub">${esc(m.name)}</span>${ICON.go}</button>
      <div class="item static">${ICON.table}<span class="grow">Kosten bisher</span><span class="sub">≈ ${fmtNum(S.cost || 0)} $</span></div>
    </section>
    <div class="group-title">Aufbau</div>
    <section class="card">
      <a class="item" href="#/einstellungen/kategorien">${ICON.tag}<span class="grow">Kategorien</span><span class="sub">${S.cats.length}</span>${ICON.go}</a>
    </section>
    <div class="group-title">Darstellung</div>
    <section class="card segwrap"><div class="seg" id="theme">
      ${[['system', 'System'], ['light', 'Hell'], ['dark', 'Dunkel']].map(([k, l]) => `<button data-t="${k}" class="${theme === k ? 'on' : ''}">${l}</button>`).join('')}
    </div></section>
    <div class="group-title">Excel</div>
    <section class="card">
      <button class="item" id="csv">${ICON.table}<span class="grow">Export für Excel</span>${ICON.go}</button>
    </section>
    <div class="group-title">Datensicherung</div>
    <section class="card">
      <button class="item" id="bk">${ICON.save}<span class="grow">Sicherung speichern</span><span class="sub">${esc(lb)}</span></button>
      <button class="item" id="rs">${ICON.load}<span class="grow">Sicherung laden</span></button>
    </section>
    <div class="group-title">Daten</div>
    <section class="card">
      <div class="item static">${ICON.disk}<span class="grow">Speicher belegt</span><span class="sub" id="usage">…</span></div>
      <button class="item danger" id="wipe">${ICON.trash}<span class="grow">Alle Belege löschen</span></button>
    </section>
    <input type="file" id="zipfile" accept=".zip,application/zip" hidden>
    <p class="version">Rechnungen · Version ${APP_VERSION}</p>`;
  $('#key').onclick = keySheet;
  $('#model').onclick = modelSheet;
  $$('#theme button').forEach(b => b.onclick = () => { S.theme = b.dataset.t; saveSettings(); applyTheme(); renderSettings(); });
  $('#csv').onclick = exportSheet;
  $('#bk').onclick = saveBackup;
  $('#rs').onclick = () => $('#zipfile').click();
  $('#zipfile').onchange = e => { const f = e.target.files[0]; e.target.value = ''; if (f) loadBackup(f); };
  $('#wipe').onclick = wipeAll;
  try {
    const est = navigator.storage && navigator.storage.estimate ? await navigator.storage.estimate() : null;
    const el = $('#usage');
    if (el && est) el.textContent = fmtNum((est.usage || 0) / 1048576, 0) + ' MB';
    else if (el) el.textContent = '–';
  } catch (e) { }
}
function keySheet() {
  const has = !!apiKey();
  const sh = openSheet(`<h3>API-Schlüssel</h3>
    <div class="f"><label for="kin">Schlüssel</label><input id="kin" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" placeholder="${has ? esc(maskKey(apiKey())) : 'sk-ant-…'}"></div>
    <div class="sheet-actions"><button class="btn block" id="ksave">Speichern</button>
    ${has ? `<div class="row2"><button class="btn ghost" id="ktest">Testen</button><button class="btn danger" id="kdel">Entfernen</button></div>` : ''}</div>`);
  const input = $('#kin', sh);
  $('#ksave', sh).onclick = async () => {
    const k = input.value.trim();
    if (!k) { toast('Bitte Schlüssel eingeben'); return; }
    if (!/^[\x21-\x7e]+$/.test(k)) { toast('Schlüssel enthält ungültige Zeichen'); return; }
    try { localStorage.setItem(API_KEY_KEY, k); } catch (e) { toast('Speichern fehlgeschlagen'); return; }
    closeSheet();
    const ok = await testKey(k);
    toast(ok === true ? 'Schlüssel gespeichert und geprüft' : ok === false ? 'Schlüssel gespeichert – aber ungültig' : ok === 'workspace' ? KEY_WS : 'Schlüssel gespeichert');
    if (ui.view === 'einstellungen') renderSettings(); else route();
    processQueue(true);
  };
  const t = $('#ktest', sh);
  if (t) t.onclick = async () => {
    t.disabled = true; t.innerHTML = '<span class="spin"></span>';
    const ok = await testKey(apiKey());
    t.disabled = false; t.textContent = 'Testen';
    toast(ok === true ? 'Schlüssel funktioniert' : ok === false ? 'Schlüssel ungültig' : ok === 'workspace' ? KEY_WS : 'Keine Verbindung');
  };
  const d = $('#kdel', sh);
  if (d) d.onclick = () => {
    if (!confirm('API-Schlüssel von diesem Gerät entfernen?')) return;
    try { localStorage.removeItem(API_KEY_KEY); } catch (e) { }
    closeSheet(); toast('Schlüssel entfernt');
    if (ui.view === 'einstellungen') renderSettings();
  };
  setTimeout(() => input.focus(), 250);
}
/* Prüft den Schlüssel kostenlos über die Modellliste.
   true / false / 'workspace' (keinem Workspace zugeordnet) / null (keine Verbindung) */
async function testKey(k) {
  try {
    const h = apiHeaders(k); delete h['content-type'];
    const r = await fetch('https://api.anthropic.com/v1/models/' + encodeURIComponent(S.model), { headers: h });
    if (r.ok) return true;
    if (r.status === 401 || r.status === 403) return false;
    if (r.status === 400) {
      let j = null;
      try { j = await r.json(); } catch (e) { }
      if (/anthropic-workspace-id/i.test((j && j.error && j.error.message) || '')) return 'workspace';
    }
    return null;
  } catch (e) { return null; }
}
const KEY_WS = 'Schlüssel ist keinem Workspace zugeordnet';
function modelSheet() {
  const sh = openSheet(`<h3>Modell</h3>
    ${MODELS.map(m => `<button class="opt${m.id === S.model ? ' on' : ''}" data-m="${m.id}"><span class="grow"><b>${esc(m.name)}</b><span>${esc(m.hint)}</span></span><span class="radio"></span></button>`).join('')}`);
  $$('.opt', sh).forEach(b => b.onclick = () => { S.model = b.dataset.m; saveSettings(); closeSheet(); renderSettings(); });
}

/* ---------------- Kategorien ---------------- */
function renderCats() {
  const all = [...BL.values()];
  $('#view').innerHTML = `
    <div class="titlebar"><button class="iconbtn" id="bk" aria-label="Zurück">${ICON.back}</button><h1>Kategorien</h1><span class="spacer"></span></div>
    <section class="card">${S.cats.map(c => `<button class="item" data-id="${esc(c.id)}"><i class="sw" style="background:${catColor(c)}"></i>
      <span class="grow">${esc(c.name)}</span><span class="sub">${all.filter(b => b.kategorie === c.id).length || ''}</span>${ICON.go}</button>`).join('')
      || '<div class="empty"><p>Keine Kategorien</p></div>'}</section>
    <button class="btn ghost block" id="add">${ICON.plus}Kategorie hinzufügen</button>`;
  $('#bk').onclick = () => back('#/einstellungen');
  $$('[data-id]').forEach(b => b.onclick = () => catSheet(catOf(b.dataset.id)));
  $('#add').onclick = () => catSheet(null);
}
function catSheet(c) {
  const isNew = !c;
  const idx = c ? S.cats.indexOf(c) : -1;
  const sh = openSheet(`<h3>${isNew ? 'Neue Kategorie' : 'Kategorie'}</h3>
    <div class="f"><label for="cn">Name</label><input id="cn" value="${esc(c ? c.name : '')}" autocomplete="off"></div>
    <div class="f"><label for="ch">Stichworte für die Zuordnung</label><textarea id="ch" rows="3">${esc(c ? c.hint : '')}</textarea></div>
    <div class="sheet-actions"><button class="btn block" id="cs">Speichern</button>
    ${isNew ? '' : `<div class="row2"><button class="btn ghost" id="cu" ${idx === 0 ? 'disabled' : ''}>${ICON.up}Nach oben</button><button class="btn danger" id="cd">Löschen</button></div>`}</div>`);
  $('#cs', sh).onclick = () => {
    const name = $('#cn', sh).value.trim(), hint = $('#ch', sh).value.trim();
    if (!name) { toast('Bitte Namen eingeben'); return; }
    if (S.cats.some(x => x !== c && x.name.toLowerCase() === name.toLowerCase())) { toast('Diese Kategorie gibt es schon'); return; }
    if (isNew) {
      const used = new Set(S.cats.map(x => x.c));
      const free = PALETTE.findIndex((_, i) => !used.has(i));
      S.cats.push({ id: uid(), name, hint, c: free });
    } else { c.name = name; c.hint = hint; }
    saveSettings(); closeSheet(); renderCats();
  };
  if (isNew) { setTimeout(() => $('#cn', sh).focus(), 250); return; }
  $('#cu', sh).onclick = () => { S.cats.splice(idx, 1); S.cats.splice(idx - 1, 0, c); saveSettings(); closeSheet(); renderCats(); };
  $('#cd', sh).onclick = async () => {
    const n = [...BL.values()].filter(b => b.kategorie === c.id);
    if (!confirm(n.length ? `Kategorie löschen? ${n.length} ${n.length === 1 ? 'Beleg verliert' : 'Belege verlieren'} die Zuordnung.` : 'Kategorie löschen?')) return;
    try {
      await dbWrite(sb => n.forEach(b => { b.kategorie = null; sb.put(b); }));
    } catch (e) { toast('Löschen fehlgeschlagen'); return; }
    S.cats = S.cats.filter(x => x !== c);
    if (ui.filter === 'c:' + c.id) ui.filter = 'alle';
    saveSettings(); closeSheet(); renderCats();
  };
}

/* ---------------- Excel-Export (CSV) ----------------
   Semikolon als Trenner, Komma als Dezimaltrennzeichen, UTF-8 mit BOM */
const csvText = s => { s = String(s == null ? '' : s).replace(/\r?\n/g, ' '); if (/^[=+\-@]/.test(s)) s = ' ' + s; return /[;"]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
const csvNum = n => isNum(n) ? round2(n).toFixed(2).replace('.', ',') : '';
function exportSheet() {
  const sh = openSheet(`<h3>Export für Excel</h3>
    <div class="grid2"><div class="f"><label for="ev">Von</label><input id="ev" type="date"></div><div class="f"><label for="eb">Bis</label><input id="eb" type="date"></div></div>
    <div class="sheet-actions"><button class="btn block" id="eb1">${ICON.table}Belege exportieren</button><button class="btn ghost block" id="eb2">Positionen exportieren</button></div>`);
  const pick = () => {
    const von = $('#ev', sh).value, bis = $('#eb', sh).value;
    return [...BL.values()].filter(b => b.status !== 'warten' && (!von || (b.datum && b.datum >= von)) && (!bis || (b.datum && b.datum <= bis)))
      .sort((a, c) => tagOf(a).localeCompare(tagOf(c)));
  };
  $('#eb1', sh).onclick = () => { const l = pick(); if (!l.length) { toast('Keine Belege im Zeitraum'); return; } exportBelege(l); closeSheet(); };
  $('#eb2', sh).onclick = () => { const l = pick(); if (!l.length) { toast('Keine Belege im Zeitraum'); return; } exportPositionen(l); closeSheet(); };
}
function csvFile(name, lines) {
  download(name + '-' + today() + '.csv', new Blob(['﻿' + lines.map(l => l.join(';')).join('\r\n') + '\r\n'], { type: 'text/csv;charset=utf-8' }));
}
function exportBelege(list) {
  const rates = [...new Set(list.flatMap(b => b.steuer.map(s => s.satz)).filter(isNum))].sort((a, c) => c - a);
  const head = ['Datum', 'Lieferant', 'Land', 'Rechnungsnummer', 'Kategorie', 'Netto', 'USt', 'Brutto', 'Zu zahlen', 'Fällig am', 'Status', 'Geprüft', 'Notiz',
    'Empfänger', 'IBAN', 'Zahlungsreferenz', 'Skonto %', 'Skonto bis', 'Mit Skonto', 'Bezahlt am', 'Gezahlt',
    ...rates.flatMap(r => ['Netto ' + fmtRate(r) + ' %', 'USt ' + fmtRate(r) + ' %'])];
  const lines = [head.map(csvText)];
  for (const b of list) {
    const c = catOf(b.kategorie);
    lines.push([csvText(fmtDate(b.datum)), csvText(b.lieferant), csvText(b.land), csvText(b.nr), csvText(c ? c.name : ''),
      csvNum(b.netto), csvNum(b.ust), csvNum(b.brutto), csvNum(b.zahlbetrag), csvText(fmtDate(b.faellig)),
      b.bezahlt ? 'bezahlt' : 'offen', b.status === 'ok' ? 'ja' : 'nein', csvText(b.notiz),
      csvText(b.empfaenger), csvText(fmtIban(b.iban)), csvText(b.referenz), isNum(b.skontoProz) ? fmtRate(b.skontoProz) : '', csvText(fmtDate(b.skontoBis)),
      csvNum(skontoInfo(b).amount), csvText(fmtDate(b.bezahltAm)), csvNum(b.bezahlt ? (isNum(b.gezahlt) ? b.gezahlt : offenBetrag(b)) : null),
      ...rates.flatMap(r => { const rs = b.steuer.filter(s => s.satz === r); return rs.length ? [csvNum(sum(rs.map(s => s.netto))), csvNum(sum(rs.map(s => s.ust)))] : ['', '']; })]);
  }
  csvFile('rechnungen-belege', lines);
  toast('Excel-Datei erstellt');
}
function exportPositionen(list) {
  const lines = [['Datum', 'Lieferant', 'Rechnungsnummer', 'Kategorie', 'Position', 'Menge', 'Betrag'].map(csvText)];
  for (const b of list) {
    const c = catOf(b.kategorie);
    for (const p of b.positionen) lines.push([csvText(fmtDate(b.datum)), csvText(b.lieferant), csvText(b.nr), csvText(c ? c.name : ''), csvText(p.text), csvText(p.menge), csvNum(p.betrag)]);
  }
  if (lines.length === 1) { toast('Keine Positionen vorhanden'); return; }
  csvFile('rechnungen-positionen', lines);
  toast('Excel-Datei erstellt');
}

/* ---------------- Datensicherung (ZIP) ----------------
   daten.json (alle Belege, Kategorien – ohne API-Schlüssel) und die
   Originaldateien unter dateien/. Einfaches ZIP ohne Kompression. */
const CRC_T = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
function crc32(buf) { let c = 0xFFFFFFFF; for (let i = 0; i < buf.length; i++) c = CRC_T[(c ^ buf[i]) & 0xFF] ^ (c >>> 8); return (c ^ 0xFFFFFFFF) >>> 0; }
async function makeZip(entries) {
  const enc = new TextEncoder(), d = new Date();
  const time = (d.getHours() << 11) | (d.getMinutes() << 5) | (d.getSeconds() >> 1);
  const date = ((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate();
  const parts = [], central = [];
  let off = 0;
  for (const e of entries) {
    const data = new Uint8Array(await e.blob.arrayBuffer());
    const crc = crc32(data), size = data.length, name = enc.encode(e.name);
    const h = new DataView(new ArrayBuffer(30));
    h.setUint32(0, 0x04034b50, true); h.setUint16(4, 20, true); h.setUint16(6, 0x0800, true); h.setUint16(8, 0, true);
    h.setUint16(10, time, true); h.setUint16(12, date, true); h.setUint32(14, crc, true); h.setUint32(18, size, true);
    h.setUint32(22, size, true); h.setUint16(26, name.length, true); h.setUint16(28, 0, true);
    parts.push(h.buffer, name, e.blob);
    const c = new DataView(new ArrayBuffer(46));
    c.setUint32(0, 0x02014b50, true); c.setUint16(4, 20, true); c.setUint16(6, 20, true); c.setUint16(8, 0x0800, true);
    c.setUint16(10, 0, true); c.setUint16(12, time, true); c.setUint16(14, date, true); c.setUint32(16, crc, true);
    c.setUint32(20, size, true); c.setUint32(24, size, true); c.setUint16(28, name.length, true);
    c.setUint32(42, off, true);
    central.push(c.buffer, name);
    off += 30 + name.length + size;
  }
  const cdSize = central.reduce((a, p) => a + p.byteLength, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, entries.length, true); end.setUint16(10, entries.length, true);
  end.setUint32(12, cdSize, true); end.setUint32(16, off, true);
  return new Blob([...parts, ...central, end.buffer], { type: 'application/zip' });
}
async function readZip(file) {
  const tl = Math.min(file.size, 65557);
  const tail = new DataView(await file.slice(file.size - tl).arrayBuffer());
  let p = -1;
  for (let i = tl - 22; i >= 0; i--) if (tail.getUint32(i, true) === 0x06054b50) { p = i; break; }
  if (p < 0) throw new Error('zip');
  const n = tail.getUint16(p + 10, true), cdSize = tail.getUint32(p + 12, true), cdOff = tail.getUint32(p + 16, true);
  const cd = new DataView(await file.slice(cdOff, cdOff + cdSize).arrayBuffer());
  const dec = new TextDecoder(), map = new Map();
  let q = 0;
  for (let k = 0; k < n; k++) {
    if (cd.getUint32(q, true) !== 0x02014b50) throw new Error('zip');
    const nl = cd.getUint16(q + 28, true), xl = cd.getUint16(q + 30, true), cl = cd.getUint16(q + 32, true);
    map.set(dec.decode(new Uint8Array(cd.buffer, q + 46, nl)), { method: cd.getUint16(q + 10, true), size: cd.getUint32(q + 20, true), lo: cd.getUint32(q + 42, true) });
    q += 46 + nl + xl + cl;
  }
  return {
    has: name => map.has(name),
    async get(name, type) {
      const e = map.get(name);
      if (!e) return null;
      if (e.method !== 0) throw new Error('zip');
      const lh = new DataView(await file.slice(e.lo, e.lo + 30).arrayBuffer());
      const start = e.lo + 30 + lh.getUint16(26, true) + lh.getUint16(28, true);
      return file.slice(start, start + e.size, type || '');
    }
  };
}
const extOf = t => t === 'application/pdf' ? 'pdf' : t === 'image/png' ? 'png' : 'jpg';
async function saveBackup() {
  if (running.size) { toast('Bitte warten, bis das Auslesen fertig ist'); return; }
  const done = veil('Sicherung wird erstellt …');
  try {
    const files = await dbAll('dateien');
    const meta = files.map(f => ({ id: f.id, beleg: f.beleg, idx: f.idx, type: f.type, name: f.name, pfad: 'dateien/' + f.id + '.' + extOf(f.type) }));
    const data = { app: 'rechnungen', format: 1, version: APP_VERSION, erstellt: new Date().toISOString(), kategorien: S.cats, belege: [...BL.values()], dateien: meta };
    const zip = await makeZip([{ name: 'daten.json', blob: new Blob([JSON.stringify(data)], { type: 'application/json' }) },
      ...files.map((f, i) => ({ name: meta[i].pfad, blob: f.blob }))]);
    download('rechnungen-sicherung-' + today() + '.zip', zip);
    S.lastBackup = new Date().toISOString(); saveSettings();
    toast('Sicherung gespeichert');
  } catch (e) { toast('Sicherung fehlgeschlagen'); }
  done();
  if (ui.view === 'einstellungen') renderSettings();
}
async function loadBackup(file) {
  if (running.size) { toast('Bitte warten, bis das Auslesen fertig ist'); return; }
  let zip, data;
  try {
    zip = await readZip(file);
    data = JSON.parse(await (await zip.get('daten.json')).text());
    if (!data || data.app !== 'rechnungen' || !Array.isArray(data.belege) || !Array.isArray(data.dateien)) throw new Error('format');
  } catch (e) { toast('Datei ist keine gültige Sicherung'); return; }
  const when = data.erstellt ? ' vom ' + fmtDate(data.erstellt.slice(0, 10)) : '';
  if (!confirm(`Sicherung${when} mit ${data.belege.length} Belegen laden? Die Belege auf diesem Gerät werden ersetzt.`)) return;
  const done = veil('Sicherung wird geladen …');
  try {
    // Erst alle Dateien finden, dann alles in einem Schritt schreiben – ganz oder gar nicht
    const files = [];
    for (const m of data.dateien) {
      const blob = await zip.get(m.pfad, m.type);
      if (blob) files.push({ id: m.id, beleg: m.beleg, idx: m.idx, type: m.type, name: m.name, blob });
    }
    const belege = data.belege.filter(b => b && typeof b.id === 'string' && Array.isArray(b.dateien)).map(fillDefaults);
    await dbWrite((sb, sf) => {
      sb.clear(); sf.clear();
      belege.forEach(b => sb.put(b));
      files.forEach(f => sf.put(f));
    });
    BL.clear(); belege.forEach(b => BL.set(b.id, b));
    const cats = Array.isArray(data.kategorien) ? data.kategorien.filter(c => c && c.id && c.name) : [];
    if (cats.length) { S.cats = cats; saveSettings(); }
    ui.filter = 'alle';
    toast('Sicherung geladen');
  } catch (e) { toast(storageError(e) === 'Speichern fehlgeschlagen' ? 'Sicherung konnte nicht geladen werden' : storageError(e)); }
  done();
  renderSettings();
}
async function wipeAll() {
  if (!BL.size) { toast('Keine Belege vorhanden'); return; }
  if (!confirm(`Wirklich alle ${BL.size} Belege mit Fotos löschen? Das kann nicht rückgängig gemacht werden.`)) return;
  try { await dbWrite((sb, sf) => { sb.clear(); sf.clear(); }); } catch (e) { toast('Löschen fehlgeschlagen'); return; }
  BL.clear();
  toast('Alle Belege gelöscht');
  renderSettings();
}

/* =============================================================
   Start
   ============================================================= */
let updateReady = false, reloaded = false;
function maybeReload() {
  if (!updateReady || reloaded || unsaved() || running.size || D || (ui.view === 'beleg' && DS && DS.dirty)) return false;
  reloaded = true; location.reload(); return true;
}
async function start() {
  loadSettings();
  applyTheme();
  mq.addEventListener && mq.addEventListener('change', () => { if ((S.theme || 'system') === 'system') { applyTheme(); route(); } });
  try {
    idb = await openDB();
    (await dbAll('belege')).forEach(b => BL.set(b.id, fillDefaults(b)));
  } catch (e) {
    $('#view').innerHTML = '<section class="card empty" style="margin-top:40px"><p>Speicher nicht verfügbar</p></section>';
    return;
  }
  const view = $('#view');
  view.addEventListener('input', onFormInput);
  view.addEventListener('change', onFormChange);
  view.addEventListener('focusout', onFormBlur);
  route();
  if (navigator.storage && navigator.storage.persist) navigator.storage.persist().catch(() => { });
  window.addEventListener('online', () => processQueue());
  processQueue();
  if ('serviceWorker' in navigator && location.protocol.startsWith('http')) {
    const hadController = !!navigator.serviceWorker.controller;
    navigator.serviceWorker.register('sw.js').catch(() => { });
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (!hadController) return;
      updateReady = true;
      if (!maybeReload()) { /* neue Version wird beim nächsten Seitenwechsel geladen */ }
    });
  }
}
start();
