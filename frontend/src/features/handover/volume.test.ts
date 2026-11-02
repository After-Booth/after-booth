import { expect, test } from 'vitest'
import { volumeText } from './volume'

test('shows liters below one cubic metre so small totals never read as zero', () => {
  expect(volumeText(3)).toBe('3L')
  expect(volumeText(648)).toBe('648L')
})

test('shows cubic metres rounded down from one cubic metre', () => {
  expect(volumeText(1000)).toBe('1.00㎥')
  expect(volumeText(1859)).toBe('1.85㎥')
})
