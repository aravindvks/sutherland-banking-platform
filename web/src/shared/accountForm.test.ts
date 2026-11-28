import { describe, expect, it } from 'vitest';
import {
  accountProblems, creditorBody, defaultScheme, exampleAccount, fieldId, hasHours, isSameBank, schemeChoices,
} from './accountForm';
import { UK, setMarket, type Market } from './market';

// What the BFF's MarketControllerTest expects /v1/market to say for the UK.
const fromBff: Market = JSON.parse(JSON.stringify(UK));

// A market that is not the UK, only to show the form follows the market. Not a real pack.
const other: Market = {
  ...UK,
  code: 'zz', currency: 'USD', locale: 'en-US', zone: 'America/New_York',
  accountIdentifier: {
    scheme: 'TEST_ROUTING',
    fields: [
      { name: 'routingNumber', label: 'Routing number', pattern: '\\d{9}', hint: 'must be 9 digits', example: '011000015' },
      { name: 'accountNumber', label: 'Account number', pattern: '\\d{4,17}', hint: 'must be 4 to 17 digits' },
    ],
  },
  paymentSchemes: [
    { code: 'INTERNAL', name: 'Transfer within the bank', instant: true, alwaysOpen: true, fee: '0.00' },
    { code: 'WIRE', name: 'Wire', instant: false, alwaysOpen: false, opens: '09:00', cutOff: '17:00', fee: '25.00' },
    { code: 'INSTANT', name: 'Instant', instant: true, alwaysOpen: true, fee: '0.00' },
  ],
  nameCheck: undefined,
};

describe('UK form, as before market packs', () => {
  it('asks for sort code and account number, under the ids the form always used', () => {
    expect(UK.accountIdentifier.fields.map((f) => f.label)).toEqual(['Sort code', 'Account number']);
    expect(fieldId('sortCode')).toBe('sort');
    expect(fieldId('accountNumber')).toBe('acc');
    expect(exampleAccount(UK)).toEqual({ sortCode: '040004', accountNumber: '12345678' });
  });

  it('sends the original request shape, fields in the original order', () => {
    const body = creditorBody('Arjun Mehta', { sortCode: '04-00-04', accountNumber: '12345678' }, UK);
    expect(JSON.stringify(body)).toBe('{"name":"Arjun Mehta","sortCode":"040004","accountNumber":"12345678"}');
  });

  it('accepts sort codes with or without dashes, as the old pattern did', () => {
    expect(accountProblems({ sortCode: '04-00-04', accountNumber: '12345678' }, UK)).toEqual([]);
    expect(accountProblems({ sortCode: '040004', accountNumber: '12345678' }, UK)).toEqual([]);
    expect(accountProblems({ sortCode: '0400', accountNumber: '1234567' }, UK)).toEqual([
      ['sortCode', 'must be 6 digits'], ['accountNumber', 'must be 8 digits']]);
  });

  it('offers Faster Payment by default and CHAPS by choice; same bank offers neither', () => {
    expect(schemeChoices(UK).map((s) => s.code)).toEqual(['FPS', 'CHAPS']);
    expect(defaultScheme(UK)).toBe('FPS');
    expect(hasHours(UK.paymentSchemes.find((s) => s.code === 'CHAPS'))).toBe(true);
    expect(hasHours(UK.paymentSchemes.find((s) => s.code === 'FPS'))).toBe(false);
    expect(isSameBank({ sortCode: '20-00-01' }, '200001', UK)).toBe(true);
    expect(isSameBank({ sortCode: '040004' }, '200001', UK)).toBe(false);
  });

  it('keeps the UK fields when an older BFF publishes none', () => {
    const { accountIdentifier: _, ...older } = fromBff;
    expect(setMarket(older).accountIdentifier).toEqual(UK.accountIdentifier);
    setMarket(UK);
  });
});

describe('another market', () => {
  it('asks for that market’s fields and sends {scheme, fields}', () => {
    expect(fieldId('routingNumber')).toBe('acct-routingNumber');
    expect(creditorBody('Pat Lee', { routingNumber: '011 000 015', accountNumber: '000123456789' }, other)).toEqual({
      name: 'Pat Lee', account: { scheme: 'TEST_ROUTING', fields: { routingNumber: '011000015', accountNumber: '000123456789' } },
    });
    expect(accountProblems({ routingNumber: '0110', accountNumber: '12' }, other).map(([n]) => n))
      .toEqual(['routingNumber', 'accountNumber']);
  });

  it('defaults to its first instant scheme and has no same-bank rule yet', () => {
    expect(schemeChoices(other).map((s) => s.code)).toEqual(['WIRE', 'INSTANT']);
    expect(defaultScheme(other)).toBe('INSTANT');
    expect(isSameBank({ routingNumber: '200001' }, '200001', other)).toBe(false);
  });
});
