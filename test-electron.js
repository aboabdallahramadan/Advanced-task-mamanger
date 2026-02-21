"use strict";
console.log('ELECTRON_RUN_AS_NODE:', process.env.ELECTRON_RUN_AS_NODE);
console.log('process.type:', process.type);
console.log('process.argv:', process.argv);
console.log('process.versions:', JSON.stringify(process.versions, null, 2));
process.exit(0);
