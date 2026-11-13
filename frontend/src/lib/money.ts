const MAX_LONG = 9_223_372_036_854_775_807n
const wholeFormatter = new Intl.NumberFormat('en-NZ', { maximumFractionDigits: 0 })

export function parseNzdToMinorUnits(value: string): bigint | null {
  const match = /^(0|[1-9]\d*)\.(\d{2})$/.exec(value.trim())
  if (!match) return null
  const amount = BigInt(match[1]) * 100n + BigInt(match[2])
  if (amount <= 0n || amount > MAX_LONG) return null
  return amount
}

export function formatNzd(value?: string): string {
  if (!value || !/^(0|[1-9]\d*)\.\d{2}$/.test(value)) return 'NZ$0.00'
  const [whole, fraction] = value.split('.')
  return `NZ$${wholeFormatter.format(BigInt(whole))}.${fraction}`
}

export function formatTransactionAmount(value: string, direction: string): string {
  return `${direction === 'INCOMING' ? '+' : '−'}${formatNzd(value)}`
}

export function sumNzd(values: string[]): string {
  const cents = values.reduce((total, value) => {
    const parsed = parseNzdToMinorUnits(value)
    return total + (parsed ?? 0n)
  }, 0n)
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`
}
