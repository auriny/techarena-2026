ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

def encode_number(val):
    chars = []
    while True:
        digit = val % 32
        val //= 32
        if val > 0:
            chars.append(ALPHABET[digit + 32])
        else:
            chars.append(ALPHABET[digit])
            break
    return "".join(chars)

def decode_numbers(text):
    nums = []
    scale = 1
    val = 0
    for c in text:
        code = ord(c)
        if 65 <= code <= 90: d = code - 65
        elif 97 <= code <= 122: d = code - 71
        elif 48 <= code <= 57: d = code + 4
        elif code == 43: d = 62
        else: d = 63
        val += (d % 32) * scale
        if d < 32:
            nums.append(val)
            val = 0
            scale = 1
        else:
            scale *= 32
    return nums

# Test roundtrip
for test_v in [0, 1, 2, 31, 32, 63, 64, 100, 20758, 418780]:
    enc = encode_number(test_v)
    dec = decode_numbers(enc)[0]
    assert dec == test_v, f"Failed on {test_v}: enc={enc}, dec={dec}"

print("Number encoding roundtrip test passed!")
