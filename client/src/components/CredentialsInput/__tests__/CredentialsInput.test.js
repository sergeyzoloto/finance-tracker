import React from 'react';
import { render, fireEvent } from '@testing-library/react';
import CredentialsInput from '../CredentialsInput';

describe('CredentialsInput', () => {
  it('renders with the correct props', () => {
    const { getByDisplayValue } = render(
      <CredentialsInput name="test" value="test value" onChange={() => {}} />,
    );

    expect(getByDisplayValue('test value')).toBeInTheDocument();
  });

  it('has the correct classes', () => {
    const { getByDisplayValue } = render(
      <CredentialsInput name="test" value="test value" onChange={() => {}} />,
    );

    const input = getByDisplayValue('test value');

    expect(input).toHaveClass('flexbox');
    expect(input).toHaveClass('border-2');
    expect(input).toHaveClass('border-solid');
    expect(input).toHaveClass('block');
    expect(input).toHaveClass('w-full');
    expect(input).toHaveClass('p-2');
    expect(input).toHaveClass('bg-white');
    expect(input).toHaveClass('border-neutral-100');
    expect(input).toHaveClass('hover:border-neutral-200');
    expect(input).toHaveClass('text-center');
    expect(input).toHaveClass('rounded');
    expect(input).toHaveClass('max-w-sm');
  });
});
