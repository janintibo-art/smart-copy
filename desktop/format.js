'use strict';

function fmtBytes(b) {
  const v = Number(b) || 0;
  if (v >= 1024 ** 3) return (v / 1024 ** 3).toFixed(2).replace('.', ',') + ' Go';
  if (v >= 1024 ** 2) return (v / 1024 ** 2).toFixed(1).replace('.', ',') + ' Mo';
  if (v >= 1024) return (v / 1024).toFixed(0) + ' Ko';
  return Math.round(v) + ' o';
}

module.exports = { fmtBytes };
