/**
 * The Hytale API shim (design doc &sect;2.3): a single central buffer against Hytale's volatile,
 * in-flux, largely-undocumented plugin API. It does double duty as the stability layer the
 * rest of the codebase depends on (fix once, centrally) and as part of the contract third
 * parties build against. Living in {@code api} is deliberate: the shim is a contract, not an
 * implementation detail.
 */
package com.chonbosmods.chemistry.api.shim;
