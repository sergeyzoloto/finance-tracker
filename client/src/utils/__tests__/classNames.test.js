import classNames from '../classNames';

describe('classNames', () => {
  it('Adds all of the args', () => {
    const result = classNames('foo', 'bar', 'baz');
    expect(result).toBe('foo bar baz');
  });

  it('Ignores falsy values', () => {
    const result = classNames('foo', null, 'bar', undefined, 'baz', false);
    expect(result).toBe('foo bar baz');
  });

  it('Returns an empty string if no arguments are provided', () => {
    const result = classNames();
    expect(result).toBe('');
  });
});
