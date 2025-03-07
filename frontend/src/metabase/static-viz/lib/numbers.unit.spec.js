import { formatNumber, formatPercent } from "./numbers";

describe("formatNumber", () => {
  it("should format a number with default options", () => {
    const number = 1500;

    const text = formatNumber(number);

    expect(text).toEqual("1,500");
  });

  it("should format a number with fractional digits", () => {
    const number = 1500.234;

    const text = formatNumber(number);

    expect(text).toEqual("1,500.23");
  });

  it("should format currency", () => {
    const number = 1500;

    const text = formatNumber(number, {
      number_style: "currency",
      currency: "USD",
      currency_style: "symbol",
    });

    expect(text).toEqual("$1,500.00");
  });

  it("should format percents", () => {
    const number = 0.867;

    const text = formatNumber(number, {
      number_style: "percent",
    });

    expect(text).toEqual("86.7%");
  });

  it("should format a number in scientific notation", () => {
    const number = 1200;

    const text = formatNumber(number, {
      number_style: "scientific",
    });

    expect(text).toEqual("1.2E3");
  });

  it("should format a number with custom number separators", () => {
    const number = 1500.234;

    const text = formatNumber(number, {
      number_separators: ".’",
    });

    expect(text).toEqual("1’500.23");
  });

  it("should format a number with fixed fractional precision", () => {
    const number = 1500;

    const text = formatNumber(number, {
      decimals: 2,
    });

    expect(text).toEqual("1,500.00");
  });

  it("should format a number with scale", () => {
    const number = 15;

    const text = formatNumber(number, {
      scale: 100,
    });

    expect(text).toEqual("1,500");
  });

  it("should format a number with a prefix and a suffix", () => {
    const number = 15;

    const text = formatNumber(number, {
      prefix: "prefix",
      suffix: "suffix",
    });

    expect(text).toEqual("prefix15suffix");
  });
});

describe("formatPercent", () => {
  it("formats percent with two decimals", () => {
    expect(formatPercent(0.12245)).toBe("12.25 %");
    expect(formatPercent(0)).toBe("0.00 %");
  });
});
