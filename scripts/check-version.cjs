const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..');
const expected = fs.readFileSync(path.join(root, 'VERSION'), 'utf8').trim();
const actual = require(path.join(root, 'desktop', 'package.json')).version;

if (actual !== expected) {
  console.error(`desktop/package.json version ${actual} does not match VERSION ${expected}`);
  process.exit(1);
}
