import crypto from 'crypto';

/**
 * AWS Signature V4 header signing, implemented directly on node:crypto so the
 * S3 sync backend needs no SDK. Covers the PUT/GET/DELETE object operations the
 * sync engine uses.
 */

const EMPTY_SHA = crypto.createHash('sha256').update('').digest('hex');

export function sigV4Headers({ method, url, region, accessKeyId, secretAccessKey, service = 's3', body, headers = {}, now = new Date() }) {
  const parsed = new URL(url);
  const amzDate = toAmzDate(now);
  const dateStamp = amzDate.slice(0, 8);
  const payloadHash = body ? crypto.createHash('sha256').update(body).digest('hex') : EMPTY_SHA;

  const allHeaders = {
    host: parsed.host,
    'x-amz-content-sha256': payloadHash,
    'x-amz-date': amzDate,
    ...lowerKeys(headers),
  };
  if (body) allHeaders['content-length'] = String(Buffer.byteLength(body));

  const signedHeaderNames = Object.keys(allHeaders).sort();
  const canonicalHeaders = signedHeaderNames.map((k) => `${k}:${String(allHeaders[k]).trim()}\n`).join('');
  const canonicalQuery = [...parsed.searchParams].sort().map(([k, v]) => `${enc(k)}=${enc(v)}`).join('&');

  const canonicalRequest = [
    method.toUpperCase(),
    parsed.pathname || '/',
    canonicalQuery,
    canonicalHeaders,
    signedHeaderNames.join(';'),
    payloadHash,
  ].join('\n');

  const scope = `${dateStamp}/${region}/${service}/aws4_request`;
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope, sha256Hex(canonicalRequest)].join('\n');

  const kDate = hmac(`AWS4${secretAccessKey}`, dateStamp);
  const kRegion = hmac(kDate, region);
  const kService = hmac(kRegion, service);
  const kSigning = hmac(kService, 'aws4_request');
  const signature = hmac(kSigning, stringToSign).toString('hex');

  return {
    ...allHeaders,
    Authorization: `AWS4-HMAC-SHA256 Credential=${accessKeyId}/${scope}, SignedHeaders=${signedHeaderNames.join(';')}, Signature=${signature}`,
  };
}

function lowerKeys(obj) {
  return Object.fromEntries(Object.entries(obj || {}).map(([k, v]) => [k.toLowerCase(), v]));
}

function hmac(key, data) {
  return crypto.createHmac('sha256', key).update(data).digest();
}

function sha256Hex(text) {
  return crypto.createHash('sha256').update(text).digest('hex');
}

function enc(s) {
  return encodeURIComponent(s).replace(/[!'()*]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase()}`);
}

function toAmzDate(d) {
  return d.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
}
