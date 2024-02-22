/**
 * Join classes together and remove any falsy values
 */

function classNames(...classes) {
  return classes.filter(Boolean).join(' ');
}

export default classNames;
