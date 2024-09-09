/**
 * Join classes together and remove any falsy values
 */

function classNames(...classes: (string | undefined | null | false)[]): string {
  return classes.filter(Boolean).join(" ");
}

export default classNames;
